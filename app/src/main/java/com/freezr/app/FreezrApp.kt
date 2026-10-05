package com.freezr.app

import android.app.Application
import android.os.UserManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.freezr.app.platform.notifications.FreezrNotifications
import com.freezr.app.platform.work.WatchdogWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FreezrApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifications: FreezrNotifications

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        // Before the first unlock (direct boot) credential storage — and WorkManager's DB — is unavailable.
        if (getSystemService(UserManager::class.java)?.isUserUnlocked != false) {
            WatchdogWorker.schedule(this)
        }
    }
}
