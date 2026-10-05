package com.freezr.app.platform.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.freezr.app.R
import com.freezr.app.platform.PlatformEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional, user-chosen uninstall protection. While device admin is active Android won't uninstall
 * the app until admin is deactivated; under Strict Mode we show a warning on the deactivate screen
 * and the accessibility service bounces the user out of that screen. We use no device policies.
 */
class FreezrDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, PlatformEntryPoint::class.java)
        // Must answer synchronously; bounded so a slow disk can never ANR the Settings app.
        val locked = runBlocking(Dispatchers.IO) { withTimeoutOrNull(1_500) { ep.strictGuard().isLocked() } } ?: false
        return context.getString(if (locked) R.string.admin_disable_warning_strict else R.string.admin_disable_warning)
    }

    override fun onEnabled(context: Context, intent: Intent) = refresh(context)
    override fun onDisabled(context: Context, intent: Intent) = refresh(context)

    private fun refresh(context: Context) {
        EntryPointAccessors.fromApplication(context.applicationContext, PlatformEntryPoint::class.java)
            .coordinator().rulesChanged()
    }
}
