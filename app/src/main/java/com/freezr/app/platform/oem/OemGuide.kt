package com.freezr.app.platform.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.freezr.app.R

enum class Oem(val displayName: String, val stepsRes: Int) {
    XIAOMI("Xiaomi / MIUI / HyperOS", R.array.oem_steps_xiaomi),
    OPPO("OPPO / ColorOS", R.array.oem_steps_oppo),
    REALME("realme UI", R.array.oem_steps_realme),
    VIVO("vivo / FuntouchOS / OriginOS", R.array.oem_steps_vivo),
    ONEPLUS("OnePlus / OxygenOS", R.array.oem_steps_oneplus),
    SAMSUNG("Samsung / One UI", R.array.oem_steps_samsung),
    HUAWEI("Huawei / EMUI / HarmonyOS", R.array.oem_steps_huawei),
    OTHER("Android", R.array.oem_steps_other),
}

data class OemShortcut(val labelRes: Int, val intent: Intent)

/**
 * Many OEM skins kill background services aggressively. We deep-link to their autostart / battery
 * screens where the component actually resolves on this device, and otherwise show written steps.
 */
object OemGuide {

    fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Oem {
        val m = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> Oem.XIAOMI
            "realme" in m -> Oem.REALME
            "oneplus" in m -> Oem.ONEPLUS
            "oppo" in m -> Oem.OPPO
            "vivo" in m || "iqoo" in m -> Oem.VIVO
            "samsung" in m -> Oem.SAMSUNG
            "huawei" in m || "honor" in m -> Oem.HUAWEI
            else -> Oem.OTHER
        }
    }

    private val AUTOSTART: Map<Oem, List<ComponentName>> = mapOf(
        Oem.XIAOMI to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        Oem.OPPO to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        Oem.REALME to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
        Oem.VIVO to listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
        Oem.ONEPLUS to listOf(
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ),
        Oem.HUAWEI to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
    )

    private val BATTERY: Map<Oem, List<ComponentName>> = mapOf(
        Oem.XIAOMI to listOf(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")),
        Oem.SAMSUNG to listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ),
        Oem.HUAWEI to listOf(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")),
    )

    /** Only shortcuts whose target activity actually exists on this device. */
    fun shortcuts(context: Context, oem: Oem): List<OemShortcut> {
        val pm = context.packageManager
        fun resolves(i: Intent) = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
        val out = ArrayList<OemShortcut>()
        AUTOSTART[oem].orEmpty().map { Intent().setComponent(it) }.firstOrNull(::resolves)
            ?.let { out += OemShortcut(R.string.oem_open_autostart, it) }
        BATTERY[oem].orEmpty().map { Intent().setComponent(it) }.firstOrNull(::resolves)
            ?.let { out += OemShortcut(R.string.oem_open_battery, it) }
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).takeIf(::resolves)
            ?.let { out += OemShortcut(R.string.oem_open_battery_optimization, it) }
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).takeIf(::resolves)
            ?.let { out += OemShortcut(R.string.oem_open_app_info, it) }
        return out
    }
}
