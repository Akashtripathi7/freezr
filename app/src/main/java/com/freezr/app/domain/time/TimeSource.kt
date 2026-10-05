package com.freezr.app.domain.time

import java.time.Instant
import java.time.ZoneId

/** The only way domain / data code may read the clock. Tests inject a fake. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
}

class SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
