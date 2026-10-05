package com.freezr.app.platform.rules

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BrowserSelector(val packageName: String, val name: String, val urlBarIds: List<String>)

@Serializable
data class InAppRule(
    val id: String,
    val title: String,
    val packageName: String,
    val viewIds: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    /** Content-description matches only count if the node is selected (e.g. the active tab). */
    val requireSelected: Boolean = false,
)

@Serializable
data class SettingsGuardRules(
    val packages: List<String> = emptyList(),
    val titleViewIds: List<String> = emptyList(),
    val markers: List<String> = emptyList(),
    val classNameHints: List<String> = emptyList(),
)

@Serializable
data class SelectorRules(
    val version: Int,
    val updated: String = "",
    val comment: String = "",
    val browsers: List<BrowserSelector> = emptyList(),
    val inAppRules: List<InAppRule> = emptyList(),
    val settingsGuard: SettingsGuardRules = SettingsGuardRules(),
)

enum class RulesSource { ASSET, OVERRIDE }

data class SelectorStats(val checks: Long = 0, val matches: Long = 0, val lastMatchAt: Instant? = null)

/**
 * Versioned selector file. Loads the bundled asset and, if present and newer, the override at
 * files/rules/blocking_rules.json (where a future remote updater would write). A malformed override
 * is ignored and reported on the selector-health screen; the bundled file is always the fallback.
 */
@Singleton
class SelectorRulesRepository @Inject constructor(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile var rules: SelectorRules = SelectorRules(version = 0); private set
    @Volatile var source: RulesSource = RulesSource.ASSET; private set
    @Volatile var loadError: String? = null; private set

    /** In-memory match counters for the debug screen. Never contains screen content. */
    val stats = ConcurrentHashMap<String, SelectorStats>()

    val overrideFile: File get() = File(context.filesDir, "rules/blocking_rules.json")

    init {
        reload()
    }

    fun reload() {
        val asset = runCatching {
            context.assets.open(ASSET).bufferedReader().use { json.decodeFromString(SelectorRules.serializer(), it.readText()) }
        }.onFailure { Log.e(TAG, "bundled rules unreadable", it) }.getOrNull() ?: SelectorRules(version = 0)

        loadError = null
        val override = if (overrideFile.exists()) {
            runCatching { json.decodeFromString(SelectorRules.serializer(), overrideFile.readText()) }
                .onFailure { loadError = "Override ignored: ${it.message?.take(160)}" }
                .getOrNull()
        } else {
            null
        }
        if (override != null && override.version > asset.version) {
            rules = override
            source = RulesSource.OVERRIDE
        } else {
            rules = asset
            source = RulesSource.ASSET
            if (override != null && loadError == null) loadError = "Override v${override.version} is not newer than bundled v${asset.version}"
        }
    }

    fun recordCheck(selectorId: String, matched: Boolean, now: Instant) {
        stats.compute(selectorId) { _, s ->
            val cur = s ?: SelectorStats()
            cur.copy(checks = cur.checks + 1, matches = cur.matches + if (matched) 1 else 0, lastMatchAt = if (matched) now else cur.lastMatchAt)
        }
    }

    private companion object {
        const val TAG = "FreezrRules"
        const val ASSET = "blocking_rules.json"
    }
}
