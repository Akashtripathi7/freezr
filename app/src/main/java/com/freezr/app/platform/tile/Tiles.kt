package com.freezr.app.platform.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.freezr.app.R
import com.freezr.app.data.repo.NothingToFreezeException
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.platform.PlatformEntryPoint
import com.freezr.app.platform.StateChangeListener
import com.freezr.app.ui.MainActivity
import com.freezr.app.ui.components.Format
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TileUpdater @Inject constructor(private val context: Context) : StateChangeListener {
    override suspend fun onStateChanged(ctx: EngineContext) {
        listOf(MasterToggleTileService::class.java, QuickFreezeTileService::class.java).forEach {
            runCatching { TileService.requestListeningState(context, ComponentName(context, it)) }
        }
    }
}

/** Shared plumbing: entry point + a scope tied to the tile's listening lifetime. */
abstract class BaseTileService : TileService() {
    protected var scope: CoroutineScope = newScope()
    protected val ep: PlatformEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, PlatformEntryPoint::class.java)
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render() }
    }

    override fun onStopListening() {
        scope.cancel()
        scope = newScope()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    protected abstract suspend fun render()

    /** Opens the app at [route], collapsing the shade (API 34 requires the PendingIntent overload). */
    protected fun openApp(route: String?) {
        val intent = if (route != null) MainActivity.routeIntent(this, route) else
            android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, route.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    protected fun Tile.apply(state: Int, label: String, subtitle: String?) {
        this.state = state
        this.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) this.subtitle = subtitle
        this.icon = Icon.createWithResource(this@BaseTileService, R.drawable.ic_stat_freezr)
        updateTile()
    }
}

/** Quick Settings: master toggle. Respects Strict Mode (opens the unlock screen instead). */
class MasterToggleTileService : BaseTileService() {
    override suspend fun render() {
        val ctx = withContext(Dispatchers.Default) { ep.stateRepo().await() }
        qsTile?.apply(
            if (ctx.masterEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            getString(R.string.app_name),
            getString(if (ctx.masterEnabled) R.string.protection_on else R.string.protection_off),
        )
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val enabled = ep.settings().current().masterEnabled
            try {
                withContext(Dispatchers.Default) { ep.controls().setMasterEnabled(!enabled) }
            } catch (_: StrictModeLockedException) {
                openApp("strict")
            }
            render()
        }
    }
}

/** Quick Settings: start a 30-minute focus on "my apps", or show the remaining time. */
class QuickFreezeTileService : BaseTileService() {
    override suspend fun render() {
        val ctx = withContext(Dispatchers.Default) { ep.stateRepo().await() }
        val now = ep.time().now()
        val qf = ctx.quickFreeze?.takeIf { now.isBefore(it.endsAt) }
        qsTile?.apply(
            if (qf != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            getString(R.string.focus_title),
            qf?.let { getString(R.string.tile_remaining, Format.duration(Duration.between(now, it.endsAt))) }
                ?: getString(R.string.tile_start_30),
        )
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val ctx = withContext(Dispatchers.Default) { ep.stateRepo().await() }
            val running = ctx.quickFreeze?.let { ep.time().now().isBefore(it.endsAt) } == true
            if (running) {
                openApp(null)
            } else {
                try {
                    withContext(Dispatchers.Default) { ep.controls().startQuickFreeze(Duration.ofMinutes(30)) }
                } catch (_: NothingToFreezeException) {
                    openApp("apps")
                }
            }
            render()
        }
    }
}
