package com.freezr.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.freezr.app.data.repo.ContextRuleRepository
import com.freezr.app.data.repo.DomainRepository
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.usage.StreakCalculator
import com.freezr.app.platform.oem.Oem
import com.freezr.app.platform.oem.OemGuide
import com.freezr.app.platform.rules.RulesSource
import com.freezr.app.platform.rules.SelectorRulesRepository
import com.freezr.app.ui.components.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class HelpersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `domain normalisation and subdomain matching`() {
        assertEquals("youtube.com", DomainRepository.normalizeDomain("https://www.YouTube.com/watch?v=1"))
        assertEquals("m.youtube.com", DomainRepository.hostOf("m.youtube.com/shorts/abc"))
        assertNull(DomainRepository.normalizeDomain("not a domain"))
        assertNull(DomainRepository.normalizeDomain("localhost"))
        assertNull(DomainRepository.hostOf("search words here"))
        assertTrue(DomainRepository.matches("m.youtube.com", "youtube.com"))
        assertTrue(DomainRepository.matches("youtube.com", "youtube.com"))
        assertFalse(DomainRepository.matches("notyoutube.com", "youtube.com"))
    }

    @Test fun `ssid normalisation strips quotes and case`() {
        assertEquals("home wifi", ContextRuleRepository.normalizeSsid("\"Home WiFi\"".replace("WiFi", "wifi")))
        assertEquals(ContextRuleRepository.normalizeSsid("Office"), ContextRuleRepository.normalizeSsid("\"office\""))
    }

    @Test fun `streaks count clean days and reset on bad days`() {
        val today = LocalDate.parse("2026-10-05")
        val first = LocalDate.parse("2026-09-20")
        val bad = setOf(LocalDate.parse("2026-09-30"))
        assertEquals(5, StreakCalculator.current(today, first, bad))
        assertEquals(10, StreakCalculator.best(today, first, bad))
        assertEquals(0, StreakCalculator.current(today, first, setOf(today)))
        assertEquals(1, StreakCalculator.current(today, today, emptySet()))
    }

    @Test fun `oem detection`() {
        assertEquals(Oem.XIAOMI, OemGuide.detect("Xiaomi", "Redmi"))
        assertEquals(Oem.REALME, OemGuide.detect("realme", "realme"))
        assertEquals(Oem.SAMSUNG, OemGuide.detect("samsung", "samsung"))
        assertEquals(Oem.VIVO, OemGuide.detect("vivo", "iQOO"))
        assertEquals(Oem.OTHER, OemGuide.detect("Google", "google"))
        // Shortcuts only include intents that resolve; never crashes on an AOSP device.
        OemGuide.shortcuts(context, Oem.XIAOMI)
    }

    @Test fun `schedule preview text`() {
        Locale.setDefault(Locale.US)
        val zone = ZoneId.of("UTC")
        // JDK 20+ formats "11:00 PM" with a narrow no-break space; compare on plain spaces.
        fun preview(r: ScheduleRule, at: Instant) = Format.schedulePreview(r, at, zone).replace('\u202F', ' ')
        fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant()
        val sleep = ScheduleRule(1, "Sleep", true, 23 * 60, 8 * 60, Days.ALL, setOf("x"))
        assertEquals("Frozen tonight 11:00 PM → 8:00 AM", preview(sleep, t("2026-10-05T18:00")))
        assertEquals("Frozen now until Tomorrow 8:00 AM", preview(sleep, t("2026-10-05T23:30")))
        val work = ScheduleRule(2, "Work", true, 9 * 60, 17 * 60, Days.WEEKDAYS, setOf("x"))
        assertEquals("Next: Monday 9:00 AM → 5:00 PM", preview(work, t("2026-10-10T12:00")))
        assertEquals("Pick at least one day", preview(work.copy(daysMask = 0), Instant.EPOCH))
    }

    @Test fun `bundled selector rules parse and an invalid override falls back`() {
        val repo = SelectorRulesRepository(context)
        assertEquals(RulesSource.ASSET, repo.source)
        assertTrue(repo.rules.version >= 1)
        assertTrue(repo.rules.browsers.any { it.packageName == "com.android.chrome" })
        assertTrue(repo.rules.inAppRules.any { it.id == "youtube_shorts" })

        val f: File = repo.overrideFile
        f.parentFile!!.mkdirs()
        f.writeText("{ not json")
        repo.reload()
        assertEquals(RulesSource.ASSET, repo.source)
        assertTrue(repo.loadError!!.startsWith("Override ignored"))

        f.writeText("""{"version": 999, "browsers": [], "inAppRules": []}""")
        repo.reload()
        assertEquals(RulesSource.OVERRIDE, repo.source)
        assertEquals(999, repo.rules.version)
        f.delete()
    }
}
