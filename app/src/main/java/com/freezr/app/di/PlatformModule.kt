package com.freezr.app.di

import com.freezr.app.platform.ReconcileStep
import com.freezr.app.platform.StateChangeListener
import com.freezr.app.platform.accessibility.ContentInspector
import com.freezr.app.platform.accessibility.InAppInspector
import com.freezr.app.platform.accessibility.SettingsGuardInspector
import com.freezr.app.platform.accessibility.WebInspector
import com.freezr.app.platform.location.GeofenceManager
import com.freezr.app.platform.location.WifiMonitor
import com.freezr.app.platform.notifications.StatusNotifier
import com.freezr.app.platform.tile.TileUpdater
import com.freezr.app.platform.widget.WidgetUpdater
import com.freezr.app.platform.work.Housekeeping
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {
    @Multibinds abstract fun inspectors(): Set<ContentInspector>

    @Binds @IntoSet abstract fun web(i: WebInspector): ContentInspector
    @Binds @IntoSet abstract fun inApp(i: InAppInspector): ContentInspector
    @Binds @IntoSet abstract fun settingsGuard(i: SettingsGuardInspector): ContentInspector

    @Binds @IntoSet abstract fun housekeeping(h: Housekeeping): ReconcileStep

    @Binds @IntoSet abstract fun statusListener(l: StatusNotifier): StateChangeListener
    @Binds @IntoSet abstract fun widgetListener(l: WidgetUpdater): StateChangeListener
    @Binds @IntoSet abstract fun tileListener(l: TileUpdater): StateChangeListener

    companion object {
        @Provides @IntoSet
        fun geofenceStep(m: GeofenceManager): ReconcileStep = object : ReconcileStep {
            override val name = "geofences"
            override suspend fun reconcile() = m.sync()
        }

        @Provides @IntoSet
        fun wifiStep(m: WifiMonitor): ReconcileStep = object : ReconcileStep {
            override val name = "wifi"
            override suspend fun reconcile() = m.start()
        }
    }
}
