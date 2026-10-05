package com.freezr.app.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.freezr.app.R
import com.freezr.app.data.repo.DomainRepository
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.data.repo.StrictRepository
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.data.settings.SurfaceBlockMode
import com.freezr.app.di.ApplicationScope
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.domain.strict.StrictModePolicy
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.rules.SelectorRulesRepository
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Synchronous, cached view of the state inspectors need on the event thread. */
@Singleton
class InspectorState @Inject constructor(
    settingsRepo: SettingsRepository,
    domainRepo: DomainRepository,
    private val stateRepo: FreezeStateRepository,
    private val time: TimeSource,
    @ApplicationScope scope: CoroutineScope,
) {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val domains: StateFlow<List<String>> = domainRepo.domains.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun anyFreezeActive(): Boolean {
        val ctx = stateRepo.snapshot.value ?: return false
        return FreezeDecisionEngine.isAnyFreezeActive(time.now(), time.zone(), ctx)
    }

    fun surfaceRuleActive(mode: SurfaceBlockMode): Boolean {
        val ctx = stateRepo.snapshot.value ?: return false
        if (!ctx.masterEnabled) return false
        return when (mode) {
            SurfaceBlockMode.OFF -> false
            SurfaceBlockMode.ALWAYS -> true
            SurfaceBlockMode.DURING_ANY_FREEZE -> anyFreezeActive()
        }
    }

    fun strictLocked(): Boolean {
        val s = settings.value
        return StrictModePolicy.isLocked(s.strictEnabled, anyFreezeActive(), s.strictUnlockedUntil, time.now())
    }
}

/** Reads the browser URL bar and blocks listed domains (and their subdomains). Best effort. */
class WebInspector @Inject constructor(
    private val state: InspectorState,
    private val rules: SelectorRulesRepository,
    private val time: TimeSource,
    private val context: Context,
) : ContentInspector {

    override val active: Flow<Boolean> = combine(state.settings, state.domains) { s, d ->
        s.webBlockMode != SurfaceBlockMode.OFF && d.isNotEmpty()
    }

    override fun interestedIn(packageName: String): Boolean =
        state.domains.value.isNotEmpty() &&
            rules.rules.browsers.any { it.packageName == packageName } &&
            state.surfaceRuleActive(state.settings.value.webBlockMode)

    override fun inspect(packageName: String, root: AccessibilityNodeInfo): ContentVerdict? {
        val browser = rules.rules.browsers.firstOrNull { it.packageName == packageName } ?: return null
        val bar = browser.urlBarIds.asSequence()
            .flatMap { id -> root.findAccessibilityNodeInfosByViewId(id).orEmpty().asSequence() }
            .firstOrNull()
        rules.recordCheck("browser:${browser.packageName}", bar != null, time.now())
        // While the user is typing in the bar, the text is a query, not the page: don't judge it.
        if (bar == null || bar.isFocused) return null
        val host = DomainRepository.hostOf(bar.text?.toString().orEmpty()) ?: return null
        val domain = state.domains.value.firstOrNull { DomainRepository.matches(host, it) } ?: return null
        return ContentVerdict.BlockSite(domain, context.getString(R.string.reason_website))
    }
}

/** Leaves short-video surfaces (Shorts, Reels …) inside otherwise allowed apps. Best effort. */
class InAppInspector @Inject constructor(
    private val state: InspectorState,
    private val rules: SelectorRulesRepository,
    private val time: TimeSource,
) : ContentInspector {

    override val active: Flow<Boolean> = state.settings.map { it.inAppBlockMode != SurfaceBlockMode.OFF && it.enabledInAppRules.isNotEmpty() }

    override fun interestedIn(packageName: String): Boolean {
        val s = state.settings.value
        return rules.rules.inAppRules.any { it.packageName == packageName && it.id in s.enabledInAppRules } &&
            state.surfaceRuleActive(s.inAppBlockMode)
    }

    override fun inspect(packageName: String, root: AccessibilityNodeInfo): ContentVerdict? {
        val enabled = state.settings.value.enabledInAppRules
        for (rule in rules.rules.inAppRules) {
            if (rule.packageName != packageName || rule.id !in enabled) continue
            val byId = rule.viewIds.any { id -> root.findAccessibilityNodeInfosByViewId(id).orEmpty().any { it.isVisibleToUser } }
            val byDesc = !byId && rule.contentDescriptions.isNotEmpty() &&
                NodeSearch.any(root, MAX_NODES) { n ->
                    val d = n.contentDescription?.toString() ?: return@any false
                    rule.contentDescriptions.any { it.equals(d, ignoreCase = true) } && (!rule.requireSelected || n.isSelected)
                }
            val matched = byId || byDesc
            rules.recordCheck(rule.id, matched, time.now())
            if (matched) return ContentVerdict.LeaveSurface(FreezeReason.IN_APP, rule.title)
        }
        return null
    }

    private companion object {
        const val MAX_NODES = 600
    }
}

/**
 * Under Strict Mode, bounces the user out of the Settings screens that could switch Freezr off:
 * our App info page, our Accessibility page and the Device admin page. Requires our app name on screen
 * plus a page marker, so ordinary Settings pages are never touched.
 */
class SettingsGuardInspector @Inject constructor(
    private val state: InspectorState,
    private val rules: SelectorRulesRepository,
    private val strict: StrictRepository,
    private val time: TimeSource,
    private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : ContentInspector {
    private val appName = context.getString(R.string.app_name)

    override val active: Flow<Boolean> = state.settings.map { it.strictEnabled && it.protectSettingsScreens }

    override fun interestedIn(packageName: String): Boolean {
        val s = state.settings.value
        return s.strictEnabled && s.protectSettingsScreens &&
            packageName in rules.rules.settingsGuard.packages && state.strictLocked()
    }

    override fun inspect(packageName: String, root: AccessibilityNodeInfo): ContentVerdict? {
        val guard = rules.rules.settingsGuard
        val titled = guard.titleViewIds.any { id ->
            root.findAccessibilityNodeInfosByViewId(id).orEmpty().any { it.text?.toString()?.contains(appName) == true }
        }
        val named = titled || root.findAccessibilityNodeInfosByText(appName).orEmpty().isNotEmpty()
        if (!named) return null
        val marked = titled || guard.markers.any { m -> root.findAccessibilityNodeInfosByText(m).orEmpty().isNotEmpty() }
        rules.recordCheck("settings_guard", marked, time.now())
        if (!marked) return null
        scope.launch { strict.recordSettingsBypassAttempt("settings_screen") }
        return ContentVerdict.LeaveSurface(FreezeReason.IN_APP, context.getString(R.string.strict_mode), recordAttempt = false)
    }
}

/** Bounded breadth-first search so a huge view tree can never stall the event thread. */
object NodeSearch {
    fun any(root: AccessibilityNodeInfo, maxNodes: Int, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val n = queue.removeFirst()
            visited++
            if (predicate(n)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return false
    }
}
