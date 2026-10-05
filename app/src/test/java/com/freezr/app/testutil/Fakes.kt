package com.freezr.app.testutil

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.freezr.app.data.db.FreezrDatabase
import com.freezr.app.data.repo.ContextRuleSource
import com.freezr.app.data.repo.ProtectedPackagesSource
import com.freezr.app.data.repo.UsageSource
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.model.ContextRule
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class FakeTimeSource(
    var instant: Instant = Instant.parse("2026-10-05T08:00:00Z"),
    var zoneId: ZoneId = ZoneId.of("UTC"),
) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    fun advance(d: Duration) { instant = instant.plus(d) }
}

class FakeProtectedSource(initial: Set<String> = setOf("com.android.settings")) : ProtectedPackagesSource {
    override val corePackages = MutableStateFlow(initial)
}

class FakeUsageSource : UsageSource {
    override val usageToday = MutableStateFlow<Map<String, Duration>>(emptyMap())
}

class FakeContextRuleSource : ContextRuleSource {
    val rules = MutableStateFlow<List<ContextRule>>(emptyList())
    override fun observe(): Flow<List<ContextRule>> = rules
    override suspend fun get(): List<ContextRule> = rules.value
}

fun inMemoryDb(): FreezrDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FreezrDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun testSettings(scope: CoroutineScope): SettingsRepository {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val file = File(ctx.cacheDir, "test-${UUID.randomUUID()}.preferences_pb")
    return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
}

class CountingNotifier : com.freezr.app.data.repo.RuleChangeNotifier {
    var count = 0
    override fun rulesChanged() { count++ }
}

class FakeSecretStore : com.freezr.app.data.repo.SecretStore {
    val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** Wires the real data layer (in-memory Room, temp DataStore) around a fake clock. */
class TestGraph(val scope: CoroutineScope, val time: FakeTimeSource = FakeTimeSource()) {
    val db = inMemoryDb()
    val settings = testSettings(scope)
    val usage = FakeUsageSource()
    val notifier = CountingNotifier()
    val stateRepo = com.freezr.app.data.repo.FreezeStateRepository(
        db, settings, FakeProtectedSource(), usage, FakeContextRuleSource(), time, "com.freezr.app", scope,
    )
    val guard = com.freezr.app.data.repo.StrictGuard(stateRepo, settings, time)
    val recorder = com.freezr.app.data.repo.InsightsRecorder(db.insightsDao(), time)
    val schedules = com.freezr.app.data.repo.ScheduleRepository(db.scheduleDao(), guard, notifier, time)
    val selection = com.freezr.app.data.repo.AppSelectionRepository(db.appSelectionDao(), time)
    val limits = com.freezr.app.data.repo.LimitRepository(db.limitDao(), guard, notifier)
    val controls = com.freezr.app.data.repo.FreezeControls(settings, guard, notifier, db.quickFreezeDao(), selection, time)
    val secrets = FakeSecretStore()
    val strict = com.freezr.app.data.repo.StrictRepository(
        settings, guard, notifier, recorder, secrets,
        com.freezr.app.domain.security.PinHasher(iterations = 1_000),
        com.freezr.app.data.repo.LocalPartnerApprovalStub(), time,
    )
    val emergency = com.freezr.app.data.repo.EmergencyRepository(db.emergencyDao(), limits, settings, stateRepo, usage, notifier, time)

    /** Stop every collector before closing the DB so no flow queries a closed database. */
    fun close() {
        kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        db.close()
    }
}
