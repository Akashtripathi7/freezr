package com.freezr.app.platform

import android.content.BroadcastReceiver
import android.util.Log
import com.freezr.app.data.repo.FreezeControls
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.notifications.FreezrNotifications
import com.freezr.app.platform.usage.UsageTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Lazy access to singletons from components that must not trigger injection eagerly
 * (direct-boot-aware receivers, tiles, widget callbacks).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlatformEntryPoint {
    fun coordinator(): EnforcementCoordinator
    fun notifications(): FreezrNotifications
    fun usageTracker(): UsageTracker
    fun settings(): SettingsRepository
    fun stateRepo(): FreezeStateRepository
    fun strictGuard(): StrictGuard
    fun time(): TimeSource
    fun controls(): FreezeControls
}

/** Runs [block] off the main thread while keeping the broadcast alive (max ~9 s of the 10 s budget). */
fun BroadcastReceiver.goAsyncWithTimeout(timeoutMs: Long = 9_000, block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            withTimeout(timeoutMs) { block() }
        } catch (t: Throwable) {
            Log.w("Freezr", "receiver work failed", t)
        } finally {
            pending.finish()
        }
    }
}
