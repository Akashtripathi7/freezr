package com.freezr.app.platform.health

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.freezr.app.R
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.data.repo.LocationNeeds
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.accessibility.FreezrAccessibilityService
import com.freezr.app.platform.admin.FreezrDeviceAdminReceiver
import com.freezr.app.platform.alarms.AlarmScheduler
import com.freezr.app.platform.notifications.FreezrNotifications
import com.freezr.app.platform.usage.UsageTracker
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthItem(val titleRes: Int, val bodyRes: Int, val critical: Boolean) {
    ACCESSIBILITY(R.string.health_accessibility, R.string.health_accessibility_body, true),
    SERVICE_RUNNING(R.string.health_service, R.string.health_service_body, true),
    USAGE_ACCESS(R.string.health_usage, R.string.health_usage_body, false),
    NOTIFICATIONS(R.string.health_notifications, R.string.health_notifications_body, false),
    EXACT_ALARMS(R.string.health_alarms, R.string.health_alarms_body, false),
    BATTERY(R.string.health_battery, R.string.health_battery_body, false),
    LOCATION(R.string.health_location, R.string.health_location_body, false),
    DEVICE_ADMIN(R.string.health_admin, R.string.health_admin_body, false),
}

data class HealthCheck(val item: HealthItem, val ok: Boolean, val relevant: Boolean = true)

data class HealthReport(val checks: List<HealthCheck>) {
    val problems: List<HealthItem> get() = checks.filter { it.relevant && !it.ok }.map { it.item }
    val criticalProblems: List<HealthItem> get() = problems.filter { it.critical }
    val isHealthy: Boolean get() = problems.isEmpty()
    val score: Int get() = checks.count { it.relevant && it.ok } * 100 / checks.count { it.relevant }.coerceAtLeast(1)
}

/**
 * Re-checks every permission and service state on demand (home onResume, watchdog, boot).
 * Pure reads from the system; no cached state.
 */
@Singleton
class PermissionHealthChecker @Inject constructor(
    private val context: Context,
    private val usage: UsageTracker,
    private val alarms: AlarmScheduler,
    private val notifications: FreezrNotifications,
    private val settings: SettingsRepository,
    private val stateRepo: FreezeStateRepository,
    private val locationNeeds: LocationNeeds,
    private val time: TimeSource,
) {
    private val serviceComponent = ComponentName(context, FreezrAccessibilityService::class.java)

    fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == serviceComponent }
    }

    fun isNotificationsAllowed(): Boolean = notifications.canPost()

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun isDeviceAdminActive(): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(ComponentName(context, FreezrDeviceAdminReceiver::class.java)) == true

    fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun check(): HealthReport {
        val a11y = isAccessibilityEnabled()
        val s = settings.current()
        val needsLocation = locationNeeds.needsLocation()
        return HealthReport(
            listOf(
                HealthCheck(HealthItem.ACCESSIBILITY, a11y),
                // Enabled in Settings but not bound = crashed or killed by the OEM.
                HealthCheck(HealthItem.SERVICE_RUNNING, FreezrAccessibilityService.isRunning, relevant = a11y),
                HealthCheck(HealthItem.USAGE_ACCESS, usage.hasPermission()),
                HealthCheck(HealthItem.NOTIFICATIONS, isNotificationsAllowed()),
                HealthCheck(HealthItem.EXACT_ALARMS, alarms.canScheduleExact()),
                HealthCheck(HealthItem.BATTERY, isIgnoringBatteryOptimizations()),
                HealthCheck(HealthItem.LOCATION, hasFineLocation() && hasBackgroundLocation(), relevant = needsLocation),
                HealthCheck(HealthItem.DEVICE_ADMIN, isDeviceAdminActive(), relevant = s.strictEnabled),
            ),
        )
    }

    /**
     * Posts a high-priority notification if blocking is broken while rules exist. Throttled to
     * once every 6 hours so a user who chooses to leave it broken isn't spammed.
     */
    suspend fun alertIfBroken(report: HealthReport) {
        val ctx = stateRepo.loadFresh()
        val hasRules = ctx.schedules.any { it.enabled } || ctx.limits.any { it.enabled } ||
            ctx.quickFreeze != null || ctx.contextRules.any { it.enabled }
        val critical = report.criticalProblems
        if (critical.isEmpty() || !hasRules || !ctx.masterEnabled) {
            notifications.cancelHealthAlert()
            return
        }
        val last = settings.current().lastHealthAlertAt
        val now = time.now()
        if (last != null && Duration.between(last, now) < Duration.ofHours(6)) return
        notifications.showHealthAlert(critical.map { context.getString(it.titleRes) })
        settings.update { lastHealthAlertAt = now }
    }

    /** Clears the throttle so the next check can alert immediately (e.g. service just died). */
    suspend fun resetAlertThrottle() = settings.update { lastHealthAlertAt = null }

    companion object {
        fun fixIntent(context: Context, item: HealthItem): Intent {
            val pkg = Uri.parse("package:${context.packageName}")
            return when (item) {
                HealthItem.ACCESSIBILITY, HealthItem.SERVICE_RUNNING -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                HealthItem.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) data = pkg
                }
                HealthItem.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                HealthItem.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                }
                // The settings list (not the direct request dialog) keeps us clear of the Play policy that restricts
                // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to a narrow set of app categories.
                HealthItem.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                HealthItem.LOCATION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                HealthItem.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, FreezrDeviceAdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_explanation))
            }
        }
    }
}
