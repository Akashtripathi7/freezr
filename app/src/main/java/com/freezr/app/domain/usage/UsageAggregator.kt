package com.freezr.app.domain.usage

import java.time.Duration
import java.time.Instant

enum class UsageEventKind { RESUMED, PAUSED, STOPPED, SCREEN_OFF, SHUTDOWN }

data class UsageEventRecord(
    val packageName: String,
    val className: String?,
    val kind: UsageEventKind,
    val time: Instant,
)

/**
 * Turns raw UsageStatsManager events into per-package foreground time inside [from, to).
 * Events are expected to include a look-back before [from] so a session that was already open
 * when the window began is clipped rather than lost. Multi-resume (split screen) is supported by
 * tracking open sessions per (package, activity).
 */
object UsageAggregator {

    fun foregroundTime(events: List<UsageEventRecord>, from: Instant, to: Instant): Map<String, Duration> {
        val open = LinkedHashMap<Pair<String, String?>, Instant>()
        val totals = HashMap<String, Long>()

        fun close(key: Pair<String, String?>, at: Instant) {
            val start = open.remove(key) ?: return
            val s = maxOf(start, from)
            val e = minOf(at, to)
            if (e.isAfter(s)) totals.merge(key.first, Duration.between(s, e).toMillis(), Long::plus)
        }

        for (ev in events.sortedBy { it.time }) {
            if (!ev.time.isBefore(to)) break
            when (ev.kind) {
                UsageEventKind.RESUMED -> {
                    val key = ev.packageName to ev.className
                    if (key !in open) open[key] = ev.time
                }
                UsageEventKind.PAUSED, UsageEventKind.STOPPED -> {
                    val key = ev.packageName to ev.className
                    if (key in open) {
                        close(key, ev.time)
                    } else if (ev.className == null) {
                        open.keys.filter { it.first == ev.packageName }.forEach { close(it, ev.time) }
                    }
                }
                UsageEventKind.SCREEN_OFF, UsageEventKind.SHUTDOWN ->
                    open.keys.toList().forEach { close(it, ev.time) }
            }
        }
        open.keys.toList().forEach { close(it, to) }
        return totals.mapValues { Duration.ofMillis(it.value) }
    }

    /** Package names with an open (resumed, not yet paused) session at the end of [events]. */
    fun openPackages(events: List<UsageEventRecord>): Set<String> {
        val open = LinkedHashSet<Pair<String, String?>>()
        for (ev in events.sortedBy { it.time }) {
            when (ev.kind) {
                UsageEventKind.RESUMED -> open += ev.packageName to ev.className
                UsageEventKind.PAUSED, UsageEventKind.STOPPED -> open -= ev.packageName to ev.className
                UsageEventKind.SCREEN_OFF, UsageEventKind.SHUTDOWN -> open.clear()
            }
        }
        return open.map { it.first }.toSet()
    }
}
