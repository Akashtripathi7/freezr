package com.freezr.app.data.repo

import com.freezr.app.data.db.BlockedDomainEntity
import com.freezr.app.data.db.ContextRuleDao
import com.freezr.app.data.db.ContextRuleEntity
import com.freezr.app.data.db.ContextRuleWithPackages
import com.freezr.app.data.db.DomainDao
import com.freezr.app.domain.model.ContextRule
import com.freezr.app.domain.model.ContextRuleType
import com.freezr.app.domain.model.TimeFilter
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Whether any enabled rule needs location (geofence or Wi-Fi SSID, which Android gates behind location). */
interface LocationNeeds {
    suspend fun needsLocation(): Boolean
}

/** Editable form of a location / Wi-Fi rule. */
data class ContextRuleDraft(
    val id: Long = 0,
    val name: String = "",
    val type: ContextRuleType = ContextRuleType.GEOFENCE,
    val enabled: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float = 150f,
    val ssid: String = "",
    val packages: Set<String> = emptySet(),
    val filter: TimeFilter? = null,
) {
    val isValid: Boolean
        get() = name.isNotBlank() && packages.isNotEmpty() && when (type) {
            ContextRuleType.GEOFENCE -> latitude != null && longitude != null && radiusMeters in 50f..5_000f
            ContextRuleType.WIFI -> ssid.isNotBlank()
        }
}

@Singleton
class ContextRuleRepository @Inject constructor(
    private val dao: ContextRuleDao,
    // Lazy: the guard and the notifier both (transitively) read rules from this repository.
    private val guardLazy: dagger.Lazy<StrictGuard>,
    private val notifierLazy: dagger.Lazy<RuleChangeNotifier>,
    private val time: TimeSource,
) : ContextRuleSource, LocationNeeds {
    private val guard: StrictGuard get() = guardLazy.get()
    private val notifier: RuleChangeNotifier get() = notifierLazy.get()

    override fun observe(): Flow<List<ContextRule>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(): List<ContextRule> = dao.getAll().map { it.toDomain() }

    override suspend fun needsLocation(): Boolean = dao.getAll().any { it.rule.enabled }

    fun observeDrafts(): Flow<List<Pair<ContextRuleDraft, Boolean>>> =
        dao.observeAll().map { list -> list.map { it.toDraft() to it.rule.active } }

    suspend fun draft(id: Long): ContextRuleDraft? = dao.get(id)?.toDraft()

    suspend fun geofences(): List<ContextRuleEntity> =
        dao.getAll().map { it.rule }.filter { it.enabled && it.type == ContextRuleType.GEOFENCE.name && it.latitude != null && it.longitude != null }

    suspend fun wifiRules(): List<ContextRuleEntity> =
        dao.getAll().map { it.rule }.filter { it.type == ContextRuleType.WIFI.name }

    suspend fun save(d: ContextRuleDraft): Long {
        if (d.id != 0L) guard.requireUnlocked()
        val existing = if (d.id != 0L) dao.get(d.id)?.rule else null
        val now = time.now().toEpochMilli()
        val id = dao.save(
            ContextRuleEntity(
                id = d.id,
                name = d.name.trim(),
                type = d.type.name,
                enabled = d.enabled,
                latitude = d.latitude,
                longitude = d.longitude,
                radiusMeters = d.radiusMeters,
                ssid = d.ssid.trim().takeIf { it.isNotEmpty() },
                active = existing?.active ?: false,
                activeUpdatedAt = existing?.activeUpdatedAt ?: now,
                filterEnabled = d.filter != null,
                filterStart = d.filter?.startMinute ?: 0,
                filterEnd = d.filter?.endMinute ?: 0,
                filterDays = d.filter?.daysMask ?: 0,
                createdAt = existing?.createdAt ?: now,
            ),
            d.packages,
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

    /** Persist inside / connected state. Only notifies when something actually changed. */
    suspend fun setActive(ids: Collection<Long>, active: Boolean) {
        val current = dao.getAll().associateBy { it.rule.id }
        var changed = false
        ids.forEach { id ->
            if (current[id]?.rule?.active != active) {
                dao.setActive(id, active, time.now().toEpochMilli())
                changed = true
            }
        }
        if (changed) notifier.rulesChanged()
    }

    /** Wi-Fi rules: active exactly when [ssid] (normalised) matches; null = not on Wi-Fi. */
    suspend fun onWifiChanged(ssid: String?) {
        val normalized = ssid?.let(::normalizeSsid)
        val rules = wifiRules()
        setActive(rules.filter { normalizeSsid(it.ssid.orEmpty()) == normalized }.map { it.id }, true)
        setActive(rules.filter { normalizeSsid(it.ssid.orEmpty()) != normalized }.map { it.id }, false)
    }

    companion object {
        fun normalizeSsid(raw: String): String = raw.trim().removeSurrounding("\"").lowercase()

        fun ContextRuleWithPackages.toDomain() = ContextRule(
            id = rule.id,
            name = rule.name,
            type = runCatching { ContextRuleType.valueOf(rule.type) }.getOrDefault(ContextRuleType.GEOFENCE),
            enabled = rule.enabled,
            active = rule.active,
            packages = packages.map { it.packageName }.toSet(),
            filter = if (rule.filterEnabled) TimeFilter(rule.filterStart, rule.filterEnd, rule.filterDays) else null,
        )

        fun ContextRuleWithPackages.toDraft() = ContextRuleDraft(
            id = rule.id,
            name = rule.name,
            type = runCatching { ContextRuleType.valueOf(rule.type) }.getOrDefault(ContextRuleType.GEOFENCE),
            enabled = rule.enabled,
            latitude = rule.latitude,
            longitude = rule.longitude,
            radiusMeters = rule.radiusMeters ?: 150f,
            ssid = rule.ssid.orEmpty(),
            packages = packages.map { it.packageName }.toSet(),
            filter = if (rule.filterEnabled) TimeFilter(rule.filterStart, rule.filterEnd, rule.filterDays) else null,
        )
    }
}

@Singleton
class DomainRepository @Inject constructor(
    private val dao: DomainDao,
    private val guard: StrictGuard,
    private val time: TimeSource,
) {
    val domains: Flow<List<String>> = dao.observe().map { list -> list.map { it.domain } }

    /** Returns the normalised domain, or null if the input isn't a plausible host name. */
    suspend fun add(input: String): String? {
        val d = normalizeDomain(input) ?: return null
        dao.insert(BlockedDomainEntity(d, time.now().toEpochMilli()))
        return d
    }

    suspend fun remove(domain: String) {
        guard.requireUnlocked()
        dao.delete(domain)
    }

    companion object {
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        /** "https://www.YouTube.com/watch?v=1" -> "youtube.com" */
        fun normalizeDomain(input: String): String? {
            val host = hostOf(input) ?: return null
            return host.removePrefix("www.").takeIf { HOST.matches(it) }
        }

        /** Extracts a lowercase host from a URL-bar string ("m.youtube.com/shorts", "https://x.com"). */
        fun hostOf(text: String): String? {
            var s = text.trim().lowercase()
            if (s.isEmpty() || ' ' in s) return null
            s = s.substringAfter("://", s)
            s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            s = s.substringAfterLast('@')
            return s.takeIf { '.' in it }
        }

        /** True if [host] is [domain] or a subdomain of it. */
        fun matches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")
    }
}
