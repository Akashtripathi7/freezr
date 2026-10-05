package com.freezr.app.data.repo

import com.freezr.app.data.db.LimitBonusEntity
import com.freezr.app.data.db.LimitDao
import com.freezr.app.data.db.UsageLimitEntity
import com.freezr.app.domain.model.UsageLimitRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LimitRepository @Inject constructor(
    private val dao: LimitDao,
    private val guard: StrictGuard,
    private val notifier: RuleChangeNotifier,
) {
    val limits: Flow<Map<String, UsageLimitRule>> = dao.observeLimits().map { list ->
        list.associate { it.packageName to UsageLimitRule(it.packageName, it.limitMinutes, it.enabled) }
    }

    /** Tightening (new limit or lower minutes) is always allowed; loosening is guarded by Strict Mode. */
    suspend fun setLimit(packageName: String, minutes: Int) {
        require(minutes in 1..24 * 60)
        val existing = dao.getLimits().firstOrNull { it.packageName == packageName }
        if (existing != null && existing.enabled && minutes > existing.limitMinutes) guard.requireUnlocked()
        dao.upsert(UsageLimitEntity(packageName, minutes, enabled = true))
        notifier.rulesChanged()
    }

    suspend fun removeLimit(packageName: String) {
        guard.requireUnlocked()
        dao.delete(packageName)
        notifier.rulesChanged()
    }

    suspend fun addBonus(packageName: String, usageDay: LocalDate, minutes: Int) {
        val day = usageDay.toString()
        val current = dao.getBonus(packageName, day)?.minutes ?: 0
        dao.upsertBonus(LimitBonusEntity(packageName, day, current + minutes))
        notifier.rulesChanged()
    }
}
