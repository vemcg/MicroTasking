// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TaskSchedulingTest {
    @Test
    fun isRapidTestingMode_onlyAboveThreshold() {
        assertTrue(isRapidTestingMode(1000))
        assertTrue(isRapidTestingMode(5000))
        assertTrue(!isRapidTestingMode(999))
        assertTrue(!isRapidTestingMode(0))
    }

    @Test
    fun nextPromptDelayMillis_insideWindow_pacesAgainstRealCloseTime() {
        // 10:00, window 9-21 (11h total, 1h elapsed), 2 prompts already delivered of 6 - paces
        // against the ~10h actually left in the window, same as before this function was reworked
        // to support a manual override outside the window.
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        val delay = nextPromptDelayMillis(now, startHour = 9, endHour = 21, promptsPerDay = 6, promptsDeliveredInWindow = 2)
        assertTrue(delay != null && delay > 0)
        val remainingWindowMillis = 10 * 60 * 60 * 1000L
        assertTrue("expected delay to fit within the remaining window, was $delay", delay!! <= remainingWindowMillis)
    }

    @Test
    fun nextPromptDelayMillis_outsideWindow_stillPaces_forManualOverride() {
        // 2:00am, window 9-21 (genuinely closed) - the window gate now lives in
        // TaskDelivery.computeNextDelayMillis, not here, so a caller that has already decided to
        // deliver anyway (a manual Resume outside the window, for testing) gets real pacing math
        // back instead of null/"wait for window to open".
        val now = LocalDateTime.of(2026, 1, 5, 2, 0)
        val delay = nextPromptDelayMillis(now, startHour = 9, endHour = 21, promptsPerDay = 6, promptsDeliveredInWindow = 0)
        assertTrue("expected a real pacing delay outside the window, got $delay", delay != null && delay > 0)
        // No real window to close against, so it's treated like a flat 24h - never inflated much
        // beyond that even with only one prompt left.
        val twentyFourHours = 24 * 60 * 60 * 1000L
        assertTrue(delay!! <= twentyFourHours)
    }

    @Test
    fun nextPromptDelayMillis_quotaExhausted_returnsNull() {
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        assertNull(nextPromptDelayMillis(now, startHour = 9, endHour = 21, promptsPerDay = 6, promptsDeliveredInWindow = 6))
    }

    @Test
    fun nextPromptDelayMillis_normalMode_floorsAtThirtySeconds() {
        // Only meaningful while rapid testing mode is off - promptsPerDay must stay under the
        // 1000 threshold or this floor check exercises the rapid 5s floor instead.
        val now = LocalDateTime.of(2026, 1, 5, 20, 59)
        val delay = nextPromptDelayMillis(now, startHour = 9, endHour = 21, promptsPerDay = 500, promptsDeliveredInWindow = 0)
        assertTrue(delay == null || delay >= 30_000L)
    }

    @Test
    fun nextPromptDelayMillis_rapidTestingMode_floorsAtFiveSeconds() {
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        val delay = nextPromptDelayMillis(now, startHour = 9, endHour = 21, promptsPerDay = 20_000, promptsDeliveredInWindow = 0)
        assertTrue(delay != null)
        assertTrue("expected the 5s rapid-testing floor, got $delay", delay!! in 5_000L..7_500L)
    }

    @Test
    fun isWithinActiveWindow_handlesWraparoundAndAlwaysActive() {
        assertTrue(isWithinActiveWindow(LocalDateTime.of(2026, 1, 5, 23, 0), startHour = 22, endHour = 6))
        assertTrue(!isWithinActiveWindow(LocalDateTime.of(2026, 1, 5, 12, 0), startHour = 22, endHour = 6))
        assertTrue(isWithinActiveWindow(LocalDateTime.of(2026, 1, 5, 3, 0), startHour = 9, endHour = 9))
    }

    @Test
    fun weekAndMonthScoreWindowMillis_switchOnPromptsPerDay() {
        val now = LocalDateTime.of(2026, 2, 10, 12, 0) // February - 28 days in 2026 (not a leap year)
        assertEquals(7 * 24 * 60 * 60 * 1000L, weekScoreWindowMillis(promptsPerDay = 500))
        assertEquals(7 * 60_000L, weekScoreWindowMillis(promptsPerDay = 1000))
        assertEquals(28 * 24 * 60 * 60 * 1000L, monthScoreWindowMillis(now, promptsPerDay = 500))
        assertEquals(28 * 60_000L, monthScoreWindowMillis(now, promptsPerDay = 1000))
    }
}
