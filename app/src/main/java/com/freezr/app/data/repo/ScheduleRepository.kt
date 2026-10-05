package com.freezr.app.data.repo

import com.freezr.app.data.db.AppGroupEntity
import com.freezr.app.data.db.AppSelectionDao
import com.freezr.app.data.db.ScheduleDao
import com.freezr.app.data.db.ScheduleEntity
import com.freezr.app.data.db.SelectedAppEntity
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleRepository @Inject constructor(
    private val dao: ScheduleDao,
    private val guard: StrictGuard,
    private val notifier: RuleChangeNotifier,
    private val time: TimeSource,
) {
    val schedules: Flow<List<ScheduleRule>> = dao.observeAll().map { list -> list.map { it.toRule() } }

    suspend fun get(id: Long): ScheduleRule? = dao.get(id)?.toRule()

    /** New schedules only add restrictions and are always allowed; editing an existing one is guarded. */
    suspend fun save(rule: ScheduleRule): Long {
        if (rule.id != 0L) guard.requireUnlocked()
        val existing = if (rule.id != 0L) dao.get(rule.id)?.schedule else null
        val id = dao.save(
            ScheduleEntity(
                id = rule.id,
                name = rule.name.trim().ifEmpty { "Schedule" },
                enabled = rule.enabled,
                startMinute = rule.startMinute,
                endMinute = rule.endMinute,
                daysMask = rule.daysMask,
                createdAt = existing?.createdAt ?: time.now().toEpochMilli(),
            ),
            rule.packages,
        )
        notifier.rulesChanged()
        return id
    }

    suspend fun delete(id: Long) {
        guard.requireUnlocked()
        dao.delete(id)
        notifier.rulesChanged()
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        if (!enabled) guard.requireUnlocked()
        dao.setEnabled(id, enabled)
        notifier.rulesChanged()
    }

    suspend fun duplicate(id: Long): Long? {
        val src = get(id) ?: return null
        return save(src.copy(id = 0, name = "${src.name} (copy)"))
    }
}

data class AppGroup(val id: Long, val name: String, val packages: Set<String>)

@Singleton
class AppSelectionRepository @Inject constructor(
    private val dao: AppSelectionDao,
    private val time: TimeSource,
) {
    val selected: Flow<Set<String>> = dao.observeSelected().map { it.toSet() }

    val groups: Flow<List<AppGroup>> = dao.observeGroups().map { list ->
        list.map { g -> AppGroup(g.group.id, g.group.name, g.packages.map { it.packageName }.toSet()) }
    }

    suspend fun selectedNow(): Set<String> = dao.getSelected().toSet()

    suspend fun setSelected(packages: Collection<String>, selected: Boolean) {
        if (selected) {
            dao.select(packages.map { SelectedAppEntity(it, time.now().toEpochMilli()) })
        } else {
            dao.unselect(packages.toList())
        }
    }

    suspend fun group(id: Long): AppGroup? = dao.getGroup(id)?.let { g ->
        AppGroup(g.group.id, g.group.name, g.packages.map { it.packageName }.toSet())
    }

    suspend fun saveGroup(group: AppGroup): Long =
        dao.saveGroup(AppGroupEntity(group.id, group.name.trim().ifEmpty { "Group" }, time.now().toEpochMilli()), group.packages)

    suspend fun deleteGroup(id: Long) = dao.deleteGroup(id)

    suspend fun onPackageRemoved(pkg: String) = dao.forgetSelected(pkg)
}
