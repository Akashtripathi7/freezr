package com.freezr.app.data.repo

import com.freezr.app.data.db.BlockAttemptEntity
import com.freezr.app.data.db.BypassLogEntity
import com.freezr.app.data.db.InsightsDao
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.domain.time.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/** Write side of the local-only insights store. Never stores screen content, only package + reason. */
@Singleton
class InsightsRecorder @Inject constructor(
    private val dao: InsightsDao,
    private val time: TimeSource,
) {
    suspend fun recordAttempt(packageName: String, reason: FreezeReason) {
        dao.insertAttempt(BlockAttemptEntity(packageName = packageName, timestamp = time.now().toEpochMilli(), reason = reason.name))
    }

    suspend fun recordBypass(method: String, success: Boolean, detail: String) {
        dao.insertBypass(BypassLogEntity(timestamp = time.now().toEpochMilli(), method = method, success = success, detail = detail))
    }

    suspend fun bypassAttemptsSince(sinceMillis: Long, method: String): Int = dao.countBypassesSince(sinceMillis, method)
}
