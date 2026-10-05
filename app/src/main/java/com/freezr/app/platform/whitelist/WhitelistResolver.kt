package com.freezr.app.platform.whitelist

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.freezr.app.data.db.WhitelistDao
import com.freezr.app.data.db.WhitelistEntity
import com.freezr.app.data.repo.ProtectedPackagesSource
import com.freezr.app.di.OwnPackage
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the never-blockable "core safety set" dynamically: whatever app currently holds the
 * dialer / SMS / home / alarm / settings / keyboard role on this device, plus fixed system UI,
 * emergency and permission-controller packages. Users cannot remove these.
 *
 * Maps apps are seeded once as removable defaults into the user whitelist table.
 */
@Singleton
class WhitelistResolver @Inject constructor(
    private val context: Context,
    private val whitelistDao: WhitelistDao,
    private val time: TimeSource,
    @OwnPackage private val ownPackage: String,
) : ProtectedPackagesSource {

    private val _core = MutableStateFlow(STATIC_CORE + ownPackage)
    override val corePackages: StateFlow<Set<String>> = _core

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _core.value = resolveCore()
    }

    /** Seed the removable defaults (maps). Safe to call repeatedly. */
    suspend fun seedDefaults() = withContext(Dispatchers.IO) {
        val maps = resolveAll(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
        whitelistDao.insert(maps.map { WhitelistEntity(it, isDefault = true, addedAt = time.now().toEpochMilli()) })
    }

    /** Human-readable categories of the core set, for the whitelist screen. */
    fun describeCore(): List<Pair<String, Set<String>>> = listOf(
        "Phone & dialer" to (resolveDialer() + PHONE_SYSTEM),
        "Messages" to resolveSms(),
        "Home screen" to resolveAll(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)),
        "Settings" to (resolveAll(Intent(Settings.ACTION_SETTINGS)) + "com.android.settings"),
        "Clock & alarms" to resolveAll(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
        "Keyboard" to resolveKeyboards(),
        "Emergency & system" to (EMERGENCY + SYSTEM),
    )

    private fun resolveCore(): Set<String> = buildSet {
        add(ownPackage)
        addAll(STATIC_CORE)
        addAll(resolveDialer())
        addAll(resolveSms())
        addAll(resolveAll(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        addAll(resolveAll(Intent(Settings.ACTION_SETTINGS)))
        addAll(resolveAll(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        addAll(resolveAll(Intent(AlarmClock.ACTION_SET_ALARM)))
        addAll(resolveAll(Intent("android.telephony.action.EMERGENCY_ASSISTANCE")))
        addAll(resolveKeyboards())
    }.filterNot { it == "android" }.toSet()

    private fun resolveDialer(): Set<String> = buildSet {
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        addAll(resolveAll(Intent(Intent.ACTION_DIAL)))
    }

    private fun resolveSms(): Set<String> = buildSet {
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(::add)
        addAll(resolveAll(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))).take(1))
    }

    private fun resolveKeyboards(): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }?.toSet()
    }.getOrNull() ?: emptySet()

    private fun resolveAll(intent: Intent): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    companion object {
        private val SYSTEM = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
        private val PHONE_SYSTEM = setOf("com.android.phone", "com.android.server.telecom")
        private val EMERGENCY = setOf(
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.samsung.android.emergency",
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
        )
        val STATIC_CORE: Set<String> = SYSTEM + PHONE_SYSTEM + EMERGENCY + "com.android.settings"
    }
}
