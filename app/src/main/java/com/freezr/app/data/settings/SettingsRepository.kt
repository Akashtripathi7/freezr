package com.freezr.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.freezr.app.domain.strict.StrictUnlockMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class SurfaceBlockMode { DURING_ANY_FREEZE, ALWAYS, OFF }

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val masterEnabled: Boolean = true,
    val preFreezeEnabled: Boolean = true,
    val preFreezeMinutes: Int = 10,
    val usageResetMinute: Int = 0,
    val limitWarningsEnabled: Boolean = true,
    val statusNotificationEnabled: Boolean = true,
    // Strict mode
    val strictEnabled: Boolean = false,
    val strictMethod: StrictUnlockMethod = StrictUnlockMethod.DELAY,
    val strictDelayMinutes: Int = 10,
    val strictConfirmWindowMinutes: Int = 3,
    val strictUnlockedUntil: Instant? = null,
    val strictDelayRequestedAt: Instant? = null,
    val pinFailures: Int = 0,
    val pinLastFailureAt: Instant? = null,
    val protectSettingsScreens: Boolean = true,
    // Emergency unlock
    val emergencyEnabled: Boolean = true,
    val emergencyWaitSeconds: Int = 30,
    val emergencyPhrase: String = DEFAULT_PHRASE,
    val emergencyPassesPerWeek: Int = 3,
    val emergencyPassMinutes: Int = 5,
    val emergencyExtraLimitMinutes: Int = 10,
    // Web & in-app
    val webBlockMode: SurfaceBlockMode = SurfaceBlockMode.DURING_ANY_FREEZE,
    val enabledInAppRules: Set<String> = emptySet(),
    val inAppBlockMode: SurfaceBlockMode = SurfaceBlockMode.DURING_ANY_FREEZE,
    // Bookkeeping
    val installedAt: Instant? = null,
    val defaultsSeeded: Boolean = false,
    val sentWarnings: Set<String> = emptySet(),
    val lastHealthAlertAt: Instant? = null,
) {
    companion object {
        const val DEFAULT_PHRASE = "I am choosing distraction over my goals"
    }
}

/** DataStore-backed settings. Everything that influences blocking is persisted here or in Room. */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = store.data.map(::read).distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(block: SettingsEditor.() -> Unit) {
        store.edit { SettingsEditor(it).block() }
    }

    private fun read(p: Preferences) = AppSettings(
        onboardingComplete = p[K.ONBOARDING] ?: false,
        masterEnabled = p[K.MASTER] ?: true,
        preFreezeEnabled = p[K.PRE_FREEZE_ENABLED] ?: true,
        preFreezeMinutes = p[K.PRE_FREEZE_MIN] ?: 10,
        usageResetMinute = p[K.RESET_MINUTE] ?: 0,
        limitWarningsEnabled = p[K.LIMIT_WARNINGS] ?: true,
        statusNotificationEnabled = p[K.STATUS_NOTIF] ?: true,
        strictEnabled = p[K.STRICT] ?: false,
        strictMethod = p[K.STRICT_METHOD]?.let { runCatching { StrictUnlockMethod.valueOf(it) }.getOrNull() }
            ?: StrictUnlockMethod.DELAY,
        strictDelayMinutes = p[K.STRICT_DELAY] ?: 10,
        strictConfirmWindowMinutes = p[K.STRICT_CONFIRM] ?: 3,
        strictUnlockedUntil = p[K.STRICT_UNLOCKED_UNTIL]?.let(Instant::ofEpochMilli),
        strictDelayRequestedAt = p[K.STRICT_DELAY_REQ]?.let(Instant::ofEpochMilli),
        pinFailures = p[K.PIN_FAILURES] ?: 0,
        pinLastFailureAt = p[K.PIN_LAST_FAILURE]?.let(Instant::ofEpochMilli),
        protectSettingsScreens = p[K.PROTECT_SETTINGS] ?: true,
        emergencyEnabled = p[K.EMERGENCY] ?: true,
        emergencyWaitSeconds = p[K.EMERGENCY_WAIT] ?: 30,
        emergencyPhrase = p[K.EMERGENCY_PHRASE] ?: AppSettings.DEFAULT_PHRASE,
        emergencyPassesPerWeek = p[K.EMERGENCY_PER_WEEK] ?: 3,
        emergencyPassMinutes = p[K.EMERGENCY_MINUTES] ?: 5,
        emergencyExtraLimitMinutes = p[K.EMERGENCY_EXTRA] ?: 10,
        webBlockMode = p[K.WEB_MODE]?.let { runCatching { SurfaceBlockMode.valueOf(it) }.getOrNull() }
            ?: SurfaceBlockMode.DURING_ANY_FREEZE,
        enabledInAppRules = p[K.IN_APP_RULES] ?: emptySet(),
        inAppBlockMode = p[K.IN_APP_MODE]?.let { runCatching { SurfaceBlockMode.valueOf(it) }.getOrNull() }
            ?: SurfaceBlockMode.DURING_ANY_FREEZE,
        installedAt = p[K.INSTALLED_AT]?.let(Instant::ofEpochMilli),
        defaultsSeeded = p[K.DEFAULTS_SEEDED] ?: false,
        sentWarnings = p[K.SENT_WARNINGS] ?: emptySet(),
        lastHealthAlertAt = p[K.LAST_HEALTH_ALERT]?.let(Instant::ofEpochMilli),
    )

    class SettingsEditor(private val p: MutablePreferences) {
        var onboardingComplete: Boolean by bool(K.ONBOARDING, false)
        var masterEnabled: Boolean by bool(K.MASTER, true)
        var preFreezeEnabled: Boolean by bool(K.PRE_FREEZE_ENABLED, true)
        var preFreezeMinutes: Int by int(K.PRE_FREEZE_MIN, 10)
        var usageResetMinute: Int by int(K.RESET_MINUTE, 0)
        var limitWarningsEnabled: Boolean by bool(K.LIMIT_WARNINGS, true)
        var statusNotificationEnabled: Boolean by bool(K.STATUS_NOTIF, true)
        var strictEnabled: Boolean by bool(K.STRICT, false)
        var strictDelayMinutes: Int by int(K.STRICT_DELAY, 10)
        var strictConfirmWindowMinutes: Int by int(K.STRICT_CONFIRM, 3)
        var pinFailures: Int by int(K.PIN_FAILURES, 0)
        var protectSettingsScreens: Boolean by bool(K.PROTECT_SETTINGS, true)
        var emergencyEnabled: Boolean by bool(K.EMERGENCY, true)
        var emergencyWaitSeconds: Int by int(K.EMERGENCY_WAIT, 30)
        var emergencyPassesPerWeek: Int by int(K.EMERGENCY_PER_WEEK, 3)
        var emergencyPassMinutes: Int by int(K.EMERGENCY_MINUTES, 5)
        var emergencyExtraLimitMinutes: Int by int(K.EMERGENCY_EXTRA, 10)
        var defaultsSeeded: Boolean by bool(K.DEFAULTS_SEEDED, false)

        var strictMethod: StrictUnlockMethod
            get() = p[K.STRICT_METHOD]?.let { StrictUnlockMethod.valueOf(it) } ?: StrictUnlockMethod.DELAY
            set(v) { p[K.STRICT_METHOD] = v.name }
        var webBlockMode: SurfaceBlockMode
            get() = p[K.WEB_MODE]?.let { SurfaceBlockMode.valueOf(it) } ?: SurfaceBlockMode.DURING_ANY_FREEZE
            set(v) { p[K.WEB_MODE] = v.name }
        var inAppBlockMode: SurfaceBlockMode
            get() = p[K.IN_APP_MODE]?.let { SurfaceBlockMode.valueOf(it) } ?: SurfaceBlockMode.DURING_ANY_FREEZE
            set(v) { p[K.IN_APP_MODE] = v.name }
        var emergencyPhrase: String
            get() = p[K.EMERGENCY_PHRASE] ?: AppSettings.DEFAULT_PHRASE
            set(v) { p[K.EMERGENCY_PHRASE] = v }
        var enabledInAppRules: Set<String>
            get() = p[K.IN_APP_RULES] ?: emptySet()
            set(v) { p[K.IN_APP_RULES] = v }
        var sentWarnings: Set<String>
            get() = p[K.SENT_WARNINGS] ?: emptySet()
            set(v) { p[K.SENT_WARNINGS] = v }
        var strictUnlockedUntil: Instant? by instant(K.STRICT_UNLOCKED_UNTIL)
        var strictDelayRequestedAt: Instant? by instant(K.STRICT_DELAY_REQ)
        var pinLastFailureAt: Instant? by instant(K.PIN_LAST_FAILURE)
        var installedAt: Instant? by instant(K.INSTALLED_AT)
        var lastHealthAlertAt: Instant? by instant(K.LAST_HEALTH_ALERT)

        private fun bool(key: Preferences.Key<Boolean>, def: Boolean) = Delegate(p, key, def)
        private fun int(key: Preferences.Key<Int>, def: Int) = Delegate(p, key, def)
        private fun instant(key: Preferences.Key<Long>) = InstantDelegate(p, key)
    }

    class Delegate<T>(private val p: MutablePreferences, private val key: Preferences.Key<T>, private val def: T) {
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): T = p[key] ?: def
        operator fun setValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>, value: T) { p[key] = value }
    }

    class InstantDelegate(private val p: MutablePreferences, private val key: Preferences.Key<Long>) {
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): Instant? = p[key]?.let(Instant::ofEpochMilli)
        operator fun setValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>, value: Instant?) {
            if (value == null) p.remove(key) else p[key] = value.toEpochMilli()
        }
    }

    private object K {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val MASTER = booleanPreferencesKey("master_enabled")
        val PRE_FREEZE_ENABLED = booleanPreferencesKey("pre_freeze_enabled")
        val PRE_FREEZE_MIN = intPreferencesKey("pre_freeze_minutes")
        val RESET_MINUTE = intPreferencesKey("usage_reset_minute")
        val LIMIT_WARNINGS = booleanPreferencesKey("limit_warnings")
        val STATUS_NOTIF = booleanPreferencesKey("status_notification")
        val STRICT = booleanPreferencesKey("strict_enabled")
        val STRICT_METHOD = stringPreferencesKey("strict_method")
        val STRICT_DELAY = intPreferencesKey("strict_delay_minutes")
        val STRICT_CONFIRM = intPreferencesKey("strict_confirm_minutes")
        val STRICT_UNLOCKED_UNTIL = longPreferencesKey("strict_unlocked_until")
        val STRICT_DELAY_REQ = longPreferencesKey("strict_delay_requested_at")
        val PIN_FAILURES = intPreferencesKey("pin_failures")
        val PIN_LAST_FAILURE = longPreferencesKey("pin_last_failure")
        val PROTECT_SETTINGS = booleanPreferencesKey("protect_settings_screens")
        val EMERGENCY = booleanPreferencesKey("emergency_enabled")
        val EMERGENCY_WAIT = intPreferencesKey("emergency_wait_seconds")
        val EMERGENCY_PHRASE = stringPreferencesKey("emergency_phrase")
        val EMERGENCY_PER_WEEK = intPreferencesKey("emergency_per_week")
        val EMERGENCY_MINUTES = intPreferencesKey("emergency_pass_minutes")
        val EMERGENCY_EXTRA = intPreferencesKey("emergency_extra_limit_minutes")
        val WEB_MODE = stringPreferencesKey("web_block_mode")
        val IN_APP_RULES = stringSetPreferencesKey("in_app_rules")
        val IN_APP_MODE = stringPreferencesKey("in_app_block_mode")
        val INSTALLED_AT = longPreferencesKey("installed_at")
        val DEFAULTS_SEEDED = booleanPreferencesKey("defaults_seeded")
        val SENT_WARNINGS = stringSetPreferencesKey("sent_warnings")
        val LAST_HEALTH_ALERT = longPreferencesKey("last_health_alert")
    }
}
