package com.freezr.app.platform

import android.util.Log
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.data.repo.RuleChangeNotifier
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.di.ApplicationScope
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.planning.AlarmPlanner
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.alarms.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** In-process signal telling the accessibility service to re-evaluate the foreground app now. */
@Singleton
class EnforcementBus @Inject constructor() {
    private val _signals = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val signals: SharedFlow<Unit> = _signals

    fun reevaluate() {
        _signals.tryEmit(Unit)
    }
}

/** Notified after every re-plan (status notification, widget, Quick Settings tiles …). */
interface StateChangeListener {
    suspend fun onStateChanged(ctx: EngineContext)
}

/** Steps that only run during a full reconcile (boot, update, service start, watchdog). */
interface ReconcileStep {
    val name: String
    suspend fun reconcile()
}

/**
 * The single place that turns "something changed" into: fresh snapshot -> alarm plan ->
 * service re-evaluation -> UI surfaces. Every entry point (UI edits, alarms, boot, time change,
 * package change, watchdog, service start) funnels through here.
 */
@Singleton
class EnforcementCoordinator @Inject constructor(
    private val stateRepo: FreezeStateRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    private val alarms: AlarmScheduler,
    private val bus: EnforcementBus,
    private val listeners: Set<@JvmSuppressWildcards StateChangeListener>,
    private val reconcileSteps: Set<@JvmSuppressWildcards ReconcileStep>,
    @ApplicationScope private val scope: CoroutineScope,
) : RuleChangeNotifier {

    private val mutex = Mutex()

    override fun rulesChanged() {
        scope.launch { replan() }
    }

    suspend fun replan() = mutex.withLock {
        stateRepo.refresh()
        val ctx = stateRepo.loadFresh()
        val s = settings.current()
        val plan = AlarmPlanner.plan(time.now(), time.zone(), ctx, s.preFreezeEnabled, s.preFreezeMinutes)
        alarms.apply(plan)
        bus.reevaluate()
        listeners.forEach { l -> runCatching { l.onStateChanged(ctx) }.onFailure { Log.w(TAG, "listener failed", it) } }
    }

    /** Full rebuild from disk; safe to call any number of times from any trigger. */
    suspend fun reconcileAll(reason: String) {
        Log.i(TAG, "reconcileAll: $reason")
        reconcileSteps.forEach { step ->
            runCatching { step.reconcile() }.onFailure { Log.w(TAG, "reconcile step ${step.name} failed", it) }
        }
        replan()
    }

    fun reconcileAsync(reason: String) {
        scope.launch { reconcileAll(reason) }
    }

    private companion object {
        const val TAG = "Freezr"
    }
}
