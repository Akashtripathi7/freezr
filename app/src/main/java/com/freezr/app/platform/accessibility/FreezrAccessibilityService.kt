package com.freezr.app.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.freezr.app.R
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.data.repo.InsightsRecorder
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.di.ApplicationScope
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.engine.TimeWindows
import com.freezr.app.domain.model.Decision
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.EnforcementBus
import com.freezr.app.platform.EnforcementCoordinator
import com.freezr.app.platform.apps.AppIconCache
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.platform.notifications.FreezrNotifications
import com.freezr.app.platform.overlay.OverlayController
import com.freezr.app.platform.usage.UsageTracker
import com.freezr.app.platform.whitelist.WhitelistResolver
import com.freezr.app.ui.MainActivity
import com.freezr.app.ui.components.AppCatalog
import com.freezr.app.ui.freeze.FreezeActivity
import com.freezr.app.ui.freeze.FreezeScreenModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The enforcement engine at runtime. System-bound, so Android restarts it (and our process) after
 * the process is killed; on every connect it rebuilds everything from disk.
 *
 * Performance budget: window-change handling only reads the cached [FreezeStateRepository.snapshot]
 * and runs the pure engine — no DB, no disk, no network on this path.
 *
 * Privacy: window content is inspected in-memory only by [ContentInspector]s for the specific
 * packages they target. Nothing read from the screen is ever logged, stored or transmitted.
 */
@AndroidEntryPoint
class FreezrAccessibilityService : AccessibilityService(), OverlayController.Callbacks {

    @Inject lateinit var stateRepo: FreezeStateRepository
    @Inject lateinit var coordinator: EnforcementCoordinator
    @Inject lateinit var bus: EnforcementBus
    @Inject lateinit var usage: UsageTracker
    @Inject lateinit var recorder: InsightsRecorder
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var time: TimeSource
    @Inject lateinit var apps: InstalledAppsRepository
    @Inject lateinit var icons: AppIconCache
    @Inject lateinit var whitelist: WhitelistResolver
    @Inject lateinit var notifications: FreezrNotifications
    @Inject lateinit var health: PermissionHealthChecker
    @Inject lateinit var inspectors: Set<@JvmSuppressWildcards ContentInspector>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val scope = MainScope()
    private lateinit var overlay: OverlayController

    /** Ephemeral view of what's on screen; always re-derivable from [getWindows]. */
    private var visible: List<VisibleApp> = emptyList()
    private var primary: String? = null
    private var currentSettings: AppSettings = AppSettings()
    private var contentEventsEnabled = false

    private var lastAttemptKey: String? = null
    private var lastAttemptAt = 0L
    private var limitJob: Job? = null
    private var settleJob: Job? = null
    /** "package|hasLimit": restart the monitor when either changes. */
    private var limitJobKey: String? = null
    private val lastInspectAt = HashMap<String, Long>()
    private var lastToastAt = 0L
    private var fallbackShownFor: String? = null

    private data class VisibleApp(val packageName: String, val pip: Boolean, val bounds: Rect?)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    usage.onForegroundChanged(null, time.now())
                    stopLimitMonitor()
                }
                // DATE_CHANGED is not delivered to manifest receivers on API 26+, so listen here too.
                Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                    coordinator.reconcileAsync("time_change")
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> scope.launch {
                    withContext(Dispatchers.Default) { usage.reconcile() }
                    refreshVisibleFromWindows()
                    reevaluate()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val catalog = object : AppCatalog {
            override fun label(packageName: String) = apps.label(packageName)
            override fun peekIcon(packageName: String) = icons.peek(packageName)
            override suspend fun loadIcon(packageName: String) = icons.load(packageName)
        }
        overlay = OverlayController(this, catalog, time::zone, time::now, this)
        applyServiceInfo(false)

        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch { settings.settings.collect { currentSettings = it } }
        scope.launch { stateRepo.snapshot.filterNotNull().collect { reevaluate() } }
        scope.launch { bus.signals.collect { reevaluate() } }
        if (inspectors.isNotEmpty()) {
            scope.launch {
                combine(inspectors.map { it.active }) { flags -> flags.any { it } }
                    .distinctUntilChanged()
                    .collect(::applyServiceInfo)
            }
        }
        // Process may have been dead: rebuild everything from disk, re-arm alarms, reconcile usage,
        // geofences and health, then judge whatever is on screen right now.
        scope.launch {
            withContext(Dispatchers.Default) {
                coordinator.reconcileAll("accessibility_connected")
                notifications.cancelHealthAlert()
            }
            refreshVisibleFromWindows()
            reevaluate()
        }
    }

    /** Only subscribe to window-content events while some inspector actually needs them. */
    private fun applyServiceInfo(contentEvents: Boolean) {
        contentEventsEnabled = contentEvents
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            (if (contentEvents) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
        info.notificationTimeout = if (contentEvents) 150 else 50
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            (if (contentEvents) AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS else 0)
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowStateChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (contentEventsEnabled) inspectContent(pkg, force = false)
        }
    }

    private fun onWindowStateChanged(pkg: String, className: String?) {
        if (isRecents(pkg, className) && overlay.isShowing) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            overlay.hide()
            return
        }
        if (isTransient(pkg, className)) return
        refreshVisibleFromWindows(eventPackage = pkg)
        if (primary != pkg) {
            primary = pkg
            usage.onForegroundChanged(pkg, time.now())
        }
        reevaluate()
        scheduleSettle()
        if (contentEventsEnabled) inspectContent(pkg, force = true)
    }

    /** System UI, keyboards and our own non-activity windows never count as "the foreground app". */
    private fun isTransient(pkg: String, className: String?): Boolean {
        if (pkg == SYSTEM_UI || pkg == "android") return true
        if (pkg == packageName) return className?.startsWith("$packageName.ui.") != true
        return className != null && className.contains("InputMethod")
    }

    private fun isRecents(pkg: String, className: String?): Boolean =
        pkg == SYSTEM_UI && className?.contains("recents", ignoreCase = true) == true

    /**
     * Rebuilds the on-screen app list. The event's package is authoritative: during launch / close
     * animations the window list can still show the previous app, so scanned windows are only merged
     * in for real multi-window (split screen, freeform) or picture-in-picture.
     */
    private fun refreshVisibleFromWindows(eventPackage: String? = null) {
        val found = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { w ->
                val root = w.root ?: return@mapNotNull null
                val p = root.packageName?.toString()
                if (p == null || isTransient(p, "$packageName.ui.")) return@mapNotNull null
                VisibleApp(p, w.isInPictureInPictureMode, Rect().also(w::getBoundsInScreen))
            }
        }.getOrNull().orEmpty()
        val multi = found.size >= 2 || found.any { it.pip }
        visible = when {
            eventPackage != null && !multi -> listOf(VisibleApp(eventPackage, false, null))
            eventPackage != null -> found + listOfNotNull(
                VisibleApp(eventPackage, false, null).takeIf { found.none { f -> f.packageName == eventPackage && !f.pip } },
            )
            else -> found.ifEmpty { listOfNotNull(primary).map { VisibleApp(it, false, null) } }
        }
        if (primary == null) primary = visible.firstOrNull { !it.pip }?.packageName
    }

    /** One more look once transition animations have settled (catches stale windows either way). */
    private fun scheduleSettle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            refreshVisibleFromWindows()
            reevaluate()
        }
    }

    /** Re-judge everything on screen against the cached snapshot. Cheap; called often. */
    private fun reevaluate() {
        if (!::overlay.isInitialized) return
        val base = stateRepo.snapshot.value ?: return
        val now = time.now()
        val zone = time.zone()
        val ctx = base.copy(usageToday = usage.liveUsage(now))

        // Site blocks belong to the browser that triggered them; drop them once it's gone.
        overlay.current?.let { m ->
            if (m.blockedDomain != null && visible.none { it.packageName == m.packageName }) overlay.hide()
        }

        val blocking = visible.filter { !it.pip }.firstNotNullOfOrNull { app ->
            (FreezeDecisionEngine.evaluate(app.packageName, now, zone, ctx) as? Decision.Frozen)?.let { app.packageName to it }
        }
        if (blocking != null) {
            showBlock(blocking.first, blocking.second, ctx)
        } else if (overlay.current?.blockedDomain == null) {
            overlay.hide()
            fallbackShownFor = null
        }

        val pip = visible.firstOrNull { it.pip && FreezeDecisionEngine.evaluate(it.packageName, now, zone, ctx) is Decision.Frozen }
        if (pip?.bounds != null) overlay.showPipCover(pip.bounds) else overlay.hidePipCover()

        primary?.let { p ->
            val key = "$p|${ctx.limits.any { it.enabled && it.packageName == p }}"
            if (limitJobKey != key) {
                startLimitMonitor(p, ctx)
                limitJobKey = key
            }
        }
    }

    private fun showBlock(pkg: String, d: Decision.Frozen, ctx: EngineContext) {
        val model = FreezeScreenModel(
            packageName = pkg,
            appLabel = apps.label(pkg),
            reason = d.reason,
            ruleName = d.ruleName,
            unfreezeAt = d.unfreezeAt,
            canEmergencyUnlock = d.canEmergencyUnlock && currentSettings.emergencyEnabled,
            passesRemaining = ctx.emergencyPassesRemaining,
        )
        if (overlay.current == model) return
        recordAttempt(pkg, d.reason, d.ruleId)
        if (!overlay.show(model) && fallbackShownFor != pkg) {
            fallbackShownFor = pkg
            startActivity(FreezeActivity.intent(this, pkg))
        }
    }

    private fun recordAttempt(pkg: String, reason: FreezeReason, ruleId: String) {
        val key = "$pkg|$ruleId"
        val nowMs = SystemClock.elapsedRealtime()
        if (key == lastAttemptKey && nowMs - lastAttemptAt < ATTEMPT_DEDUPE_MS) return
        lastAttemptKey = key
        lastAttemptAt = nowMs
        appScope.launch { recorder.recordAttempt(pkg, reason) }
    }

    // ---- Overlay callbacks ----

    override fun onGoHome(model: FreezeScreenModel) {
        if (model.blockedDomain != null) {
            overlay.hide()
            // Let the overlay window go first so BACK reaches the browser, not us.
            scope.launch {
                delay(60)
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        scope.launch {
            delay(350)
            refreshVisibleFromWindows()
            reevaluate()
        }
    }

    override fun onEmergency(model: FreezeScreenModel) {
        startActivity(MainActivity.emergencyIntent(this, model.packageName))
        overlay.hide()
    }

    override fun onExpired(model: FreezeScreenModel) = reevaluate()

    // ---- Usage limits while in the foreground ----

    private fun startLimitMonitor(pkg: String, ctx: EngineContext) {
        stopLimitMonitor()
        if (ctx.limits.none { it.enabled && it.packageName == pkg }) return
        limitJob = scope.launch {
            while (isActive) {
                checkLimit(pkg)
                delay(LIMIT_TICK_MS)
            }
        }
    }

    private fun stopLimitMonitor() {
        limitJob?.cancel()
        limitJob = null
        limitJobKey = null
    }

    private suspend fun checkLimit(pkg: String) {
        val ctx = stateRepo.snapshot.value ?: return
        val limit = ctx.limits.firstOrNull { it.enabled && it.packageName == pkg } ?: return
        val s = currentSettings
        val now = time.now()
        val used = usage.usedFor(pkg, now)
        val allowed = Duration.ofMinutes(limit.limitMinutes.toLong() + (ctx.limitBonusMinutes[pkg] ?: 0))
        val remaining = allowed.minus(used)
        val pct = (used.toMillis() * 100 / allowed.toMillis().coerceAtLeast(1)).toInt()
        val day = TimeWindows.usageDay(now, time.zone(), s.usageResetMinute).toString()
        val stage = when {
            pct >= 100 -> "100"
            s.preFreezeEnabled && remaining <= Duration.ofMinutes(s.preFreezeMinutes.toLong()) -> "pre"
            pct >= 80 -> "80"
            else -> null
        }
        if (stage != null && s.limitWarningsEnabled) {
            val key = "$day|$pkg|$stage|${ctx.limitBonusMinutes[pkg] ?: 0}"
            if (key !in s.sentWarnings) {
                when (stage) {
                    "pre" -> notifications.showPreFreeze(now.plus(remaining), setOf(pkg), listOf(getString(R.string.reason_limit)))
                    else -> notifications.showLimitWarning(pkg, pct.coerceAtMost(100), remaining)
                }
                settings.update { sentWarnings = sentWarnings.filter { it.startsWith(day) }.toSet() + key }
            }
        }
        if (pct >= 100) reevaluate()
    }

    // ---- Window content (web / in-app / Settings protection) ----

    private fun inspectContent(pkg: String, force: Boolean) {
        val interested = inspectors.filter { it.interestedIn(pkg) }
        if (interested.isEmpty()) {
            if (overlay.current?.let { it.blockedDomain != null && it.packageName == pkg } == true) overlay.hide()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - (lastInspectAt[pkg] ?: 0) < INSPECT_THROTTLE_MS) return
        lastInspectAt[pkg] = nowMs
        val root = rootFor(pkg) ?: return
        val verdict = interested.firstNotNullOfOrNull { runCatching { it.inspect(pkg, root) }.getOrNull() }
        when (verdict) {
            null -> if (overlay.current?.let { it.blockedDomain != null && it.packageName == pkg } == true) overlay.hide()
            is ContentVerdict.LeaveSurface -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (verdict.recordAttempt) recordAttempt(pkg, verdict.reason, verdict.label)
                if (nowMs - lastToastAt > TOAST_GAP_MS) {
                    lastToastAt = nowMs
                    Toast.makeText(this, getString(R.string.surface_frozen_toast, verdict.label), Toast.LENGTH_SHORT).show()
                }
            }
            is ContentVerdict.BlockSite -> {
                val model = FreezeScreenModel(
                    packageName = pkg,
                    appLabel = apps.label(pkg),
                    reason = FreezeReason.WEBSITE,
                    ruleName = verdict.ruleName,
                    unfreezeAt = null,
                    canEmergencyUnlock = false,
                    passesRemaining = 0,
                    blockedDomain = verdict.domain,
                )
                if (overlay.current != model) {
                    recordAttempt(pkg, FreezeReason.WEBSITE, verdict.domain)
                    overlay.show(model)
                }
            }
        }
    }

    /** Root of [pkg]'s application window, even when our own overlay holds focus. */
    private fun rootFor(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) return it }
        return runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg }?.root
        }.getOrNull()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screenReceiver) }
        if (::overlay.isInitialized) overlay.destroy()
        scope.cancel()
        // Disabled, crashed or killed: tell the user right away (matrix row 8).
        if (::appScope.isInitialized) {
            appScope.launch {
                runCatching {
                    health.resetAlertThrottle()
                    health.alertIfBroken(health.check())
                }.onFailure { Log.w(TAG, "health alert failed", it) }
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FreezrA11y"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val ATTEMPT_DEDUPE_MS = 30_000L
        private const val LIMIT_TICK_MS = 10_000L
        private const val INSPECT_THROTTLE_MS = 350L
        private const val TOAST_GAP_MS = 4_000L
        private const val SETTLE_MS = 600L

        /** True while the system has us bound. Read by the health checker. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
