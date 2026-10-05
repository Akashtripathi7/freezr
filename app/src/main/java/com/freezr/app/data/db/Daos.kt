package com.freezr.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScheduleDao {
    @Transaction
    @Query("SELECT * FROM schedule ORDER BY start_minute, name")
    abstract fun observeAll(): Flow<List<ScheduleWithPackages>>

    @Transaction
    @Query("SELECT * FROM schedule ORDER BY start_minute, name")
    abstract suspend fun getAll(): List<ScheduleWithPackages>

    @Transaction
    @Query("SELECT * FROM schedule WHERE id = :id")
    abstract suspend fun get(id: Long): ScheduleWithPackages?

    @Insert
    protected abstract suspend fun insertSchedule(s: ScheduleEntity): Long

    @Upsert
    protected abstract suspend fun upsertSchedule(s: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPackages(p: List<SchedulePackageEntity>)

    @Query("DELETE FROM schedule_package WHERE schedule_id = :id")
    protected abstract suspend fun clearPackages(id: Long)

    @Query("DELETE FROM schedule WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE schedule SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Schedule row and its package set are written atomically. */
    @Transaction
    open suspend fun save(schedule: ScheduleEntity, packages: Set<String>): Long {
        val id = if (schedule.id == 0L) insertSchedule(schedule) else schedule.id.also { upsertSchedule(schedule) }
        clearPackages(id)
        insertPackages(packages.map { SchedulePackageEntity(id, it) })
        return id
    }
}

@Dao
abstract class AppSelectionDao {
    @Query("SELECT package_name FROM selected_app")
    abstract fun observeSelected(): Flow<List<String>>

    @Query("SELECT package_name FROM selected_app")
    abstract suspend fun getSelected(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun select(apps: List<SelectedAppEntity>)

    @Query("DELETE FROM selected_app WHERE package_name IN (:packages)")
    abstract suspend fun unselect(packages: List<String>)

    @Transaction
    @Query("SELECT * FROM app_group ORDER BY name")
    abstract fun observeGroups(): Flow<List<AppGroupWithPackages>>

    @Transaction
    @Query("SELECT * FROM app_group WHERE id = :id")
    abstract suspend fun getGroup(id: Long): AppGroupWithPackages?

    @Insert
    protected abstract suspend fun insertGroup(g: AppGroupEntity): Long

    @Upsert
    protected abstract suspend fun upsertGroup(g: AppGroupEntity)

    @Query("DELETE FROM app_group_package WHERE group_id = :id")
    protected abstract suspend fun clearGroup(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertGroupPackages(p: List<AppGroupPackageEntity>)

    @Query("DELETE FROM app_group WHERE id = :id")
    abstract suspend fun deleteGroup(id: Long)

    @Transaction
    open suspend fun saveGroup(group: AppGroupEntity, packages: Set<String>): Long {
        val id = if (group.id == 0L) insertGroup(group) else group.id.also { upsertGroup(group) }
        clearGroup(id)
        insertGroupPackages(packages.map { AppGroupPackageEntity(id, it) })
        return id
    }

    /** Remove an uninstalled package everywhere it could be referenced. */
    @Query("DELETE FROM selected_app WHERE package_name = :pkg")
    abstract suspend fun forgetSelected(pkg: String)
}

@Dao
interface LimitDao {
    @Query("SELECT * FROM usage_limit ORDER BY package_name")
    fun observeLimits(): Flow<List<UsageLimitEntity>>

    @Query("SELECT * FROM usage_limit")
    suspend fun getLimits(): List<UsageLimitEntity>

    @Upsert
    suspend fun upsert(limit: UsageLimitEntity)

    @Query("DELETE FROM usage_limit WHERE package_name = :pkg")
    suspend fun delete(pkg: String)

    @Query("SELECT * FROM limit_bonus")
    fun observeBonuses(): Flow<List<LimitBonusEntity>>

    @Query("SELECT * FROM limit_bonus WHERE package_name = :pkg AND day = :day")
    suspend fun getBonus(pkg: String, day: String): LimitBonusEntity?

    @Upsert
    suspend fun upsertBonus(b: LimitBonusEntity)

    @Query("SELECT * FROM limit_bonus WHERE day = :day")
    suspend fun getBonuses(day: String): List<LimitBonusEntity>

    @Query("DELETE FROM limit_bonus WHERE day < :beforeDay")
    suspend fun pruneBonuses(beforeDay: String)
}

@Dao
abstract class EmergencyDao {
    @Query("SELECT * FROM emergency_pass WHERE expires_at > :now")
    abstract fun observeActivePasses(now: Long): Flow<List<EmergencyPassEntity>>

    @Query("SELECT * FROM emergency_pass WHERE expires_at > :now")
    abstract suspend fun getActivePasses(now: Long): List<EmergencyPassEntity>

    @Query("SELECT * FROM emergency_pass")
    abstract fun observePasses(): Flow<List<EmergencyPassEntity>>

    @Insert
    abstract suspend fun insertPass(p: EmergencyPassEntity): Long

    @Query("DELETE FROM emergency_pass WHERE expires_at <= :now")
    abstract suspend fun pruneExpired(now: Long)

    @Insert
    abstract suspend fun insertLog(l: EmergencyLogEntity)

    @Query("SELECT timestamp FROM emergency_log WHERE timestamp >= :since")
    abstract fun observeLogTimes(since: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM emergency_log WHERE timestamp >= :since")
    abstract suspend fun countSince(since: Long): Int

    @Query("SELECT * FROM emergency_log WHERE timestamp >= :since ORDER BY timestamp DESC")
    abstract fun observeLog(since: Long): Flow<List<EmergencyLogEntity>>

    @Query("DELETE FROM emergency_log WHERE timestamp < :before")
    abstract suspend fun pruneLog(before: Long)

    /** Pass + log entry are written together, so a granted pass is always counted. */
    @Transaction
    open suspend fun grantPass(pass: EmergencyPassEntity, log: EmergencyLogEntity) {
        insertPass(pass)
        insertLog(log)
    }
}

@Dao
interface QuickFreezeDao {
    @Query("SELECT * FROM quick_freeze WHERE id = 0")
    fun observe(): Flow<QuickFreezeEntity?>

    @Query("SELECT * FROM quick_freeze WHERE id = 0")
    suspend fun get(): QuickFreezeEntity?

    @Upsert
    suspend fun set(q: QuickFreezeEntity)

    @Query("DELETE FROM quick_freeze")
    suspend fun clear()
}

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelist_entry ORDER BY package_name")
    fun observe(): Flow<List<WhitelistEntity>>

    @Query("SELECT * FROM whitelist_entry")
    suspend fun getAll(): List<WhitelistEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entries: List<WhitelistEntity>)

    @Query("DELETE FROM whitelist_entry WHERE package_name = :pkg")
    suspend fun delete(pkg: String)
}

data class PackageCount(
    @androidx.room.ColumnInfo(name = "package_name") val packageName: String,
    val count: Int,
)

data class DayCount(val day: String, val count: Int)

@Dao
interface InsightsDao {
    @Insert
    suspend fun insertAttempt(a: BlockAttemptEntity)

    @Query("SELECT package_name, COUNT(*) AS count FROM block_attempt WHERE timestamp >= :since GROUP BY package_name ORDER BY count DESC")
    fun observeAttemptsByPackage(since: Long): Flow<List<PackageCount>>

    @Query("SELECT COUNT(*) FROM block_attempt WHERE timestamp >= :since")
    fun observeAttemptCount(since: Long): Flow<Int>

    @Query("SELECT * FROM block_attempt WHERE timestamp >= :since")
    fun observeAttempts(since: Long): Flow<List<BlockAttemptEntity>>

    @Insert
    suspend fun insertBypass(b: BypassLogEntity)

    @Query("SELECT * FROM bypass_log WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun observeBypasses(since: Long): Flow<List<BypassLogEntity>>

    @Query("SELECT COUNT(*) FROM bypass_log WHERE timestamp >= :since AND method = :method")
    suspend fun countBypassesSince(since: Long, method: String): Int

    @Upsert
    suspend fun upsertDailyUsage(rows: List<DailyUsageEntity>)

    @Query("SELECT * FROM daily_usage WHERE day >= :fromDay ORDER BY day")
    fun observeDailyUsage(fromDay: String): Flow<List<DailyUsageEntity>>

    @Query("DELETE FROM block_attempt WHERE timestamp < :before")
    suspend fun pruneAttempts(before: Long)

    @Query("DELETE FROM bypass_log WHERE timestamp < :before")
    suspend fun pruneBypasses(before: Long)

    @Query("DELETE FROM daily_usage WHERE day < :beforeDay")
    suspend fun pruneDailyUsage(beforeDay: String)
}

@Dao
abstract class ContextRuleDao {
    @Transaction
    @Query("SELECT * FROM context_rule ORDER BY name")
    abstract fun observeAll(): Flow<List<ContextRuleWithPackages>>

    @Transaction
    @Query("SELECT * FROM context_rule ORDER BY name")
    abstract suspend fun getAll(): List<ContextRuleWithPackages>

    @Transaction
    @Query("SELECT * FROM context_rule WHERE id = :id")
    abstract suspend fun get(id: Long): ContextRuleWithPackages?

    @Insert
    protected abstract suspend fun insertRule(r: ContextRuleEntity): Long

    @Upsert
    protected abstract suspend fun upsertRule(r: ContextRuleEntity)

    @Query("DELETE FROM context_rule_package WHERE rule_id = :id")
    protected abstract suspend fun clearPackages(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPackages(p: List<ContextRulePackageEntity>)

    @Query("DELETE FROM context_rule WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE context_rule SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE context_rule SET active = :active, active_updated_at = :at WHERE id = :id")
    abstract suspend fun setActive(id: Long, active: Boolean, at: Long)

    @Transaction
    open suspend fun save(rule: ContextRuleEntity, packages: Set<String>): Long {
        val id = if (rule.id == 0L) insertRule(rule) else rule.id.also { upsertRule(rule) }
        clearPackages(id)
        insertPackages(packages.map { ContextRulePackageEntity(id, it) })
        return id
    }
}

@Dao
interface DomainDao {
    @Query("SELECT * FROM blocked_domain ORDER BY domain")
    fun observe(): Flow<List<BlockedDomainEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(d: BlockedDomainEntity)

    @Query("DELETE FROM blocked_domain WHERE domain = :domain")
    suspend fun delete(domain: String)
}
