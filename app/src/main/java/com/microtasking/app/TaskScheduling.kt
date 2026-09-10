// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Whether [hour] (0-23) falls inside the active window [startHour, endHour). An end hour that is
 * less than or equal to the start hour wraps past midnight (e.g. start=22, end=6). A start hour
 * equal to the end hour (including 0/24) means the window is always active.
 */
fun isHourActive(hour: Int, startHour: Int, endHour: Int): Boolean =
    if (endHour > startHour) hour in startHour until endHour else hour >= startHour || hour < endHour

fun isWithinActiveWindow(now: LocalDateTime, startHour: Int, endHour: Int): Boolean =
    isHourActive(now.hour, startHour, endHour)

/** The instant the current (or most recently opened) window occurrence began. */
fun currentWindowStart(now: LocalDateTime, startHour: Int, endHour: Int): LocalDateTime {
    if (startHour == endHour) return now.toLocalDate().atStartOfDay()
    var open = now.toLocalDate().atTime(LocalTime.of(startHour % 24, 0))
    if (open.isAfter(now)) open = open.minusDays(1)
    return open
}

/** Millis from [now] until the active window opens next, or 0 if already inside it. */
fun millisUntilWindowOpens(now: LocalDateTime, startHour: Int, endHour: Int): Long {
    if (startHour == endHour || isWithinActiveWindow(now, startHour, endHour)) return 0L
    var open = now.toLocalDate().atTime(LocalTime.of(startHour % 24, 0))
    if (!open.isAfter(now)) open = open.plusDays(1)
    return Duration.between(now, open).toMillis()
}

/** Millis from [now] until the active window closes, or null if it never closes. */
fun millisUntilWindowCloses(now: LocalDateTime, startHour: Int, endHour: Int): Long? {
    if (startHour == endHour) return null
    if (!isWithinActiveWindow(now, startHour, endHour)) return 0L
    var close = now.toLocalDate().atTime(LocalTime.of(endHour % 24, 0))
    if (!close.isAfter(now)) close = close.plusDays(1)
    return Duration.between(now, close).toMillis()
}

/**
 * Threshold on "prompts per day" above which the app treats itself as being deliberately
 * stress-tested rather than used normally - no rebuild needed, just dial the Settings "prompts per
 * day" field above/below this in the running app. Above it: the queue-delivery floor drops to 5
 * seconds instead of 30, and the "this week"/"this month" score-screen windows drop to minutes
 * instead of days (see below), so a developer can actually sit and watch the pacing/bucketing.
 */
private const val RAPID_TESTING_THRESHOLD = 1000

fun isRapidTestingMode(promptsPerDay: Int): Boolean = promptsPerDay >= RAPID_TESTING_THRESHOLD

/**
 * Floor on the interval [fixedDispatchIntervalMillis] can return. Without this, a short window
 * with a large "prompts per day" makes windowLength / (N - 1) round down toward zero, which
 * would fire a rapid-fire burst of deliveries (each one doing disk I/O and posting a
 * notification) tight enough to ANR the app. Rapid-testing mode drops it to 5s so a developer
 * can actually watch the cadence.
 */
fun minDelayMillis(promptsPerDay: Int): Long = if (isRapidTestingMode(promptsPerDay)) 5_000L else 30_000L

private val TWENTY_FOUR_HOURS_MILLIS = Duration.ofHours(24).toMillis()

/**
 * Full length of the active window in millis: (endHour - endHour) worth of hours, wrapping past
 * midnight when endHour <= startHour. A start hour equal to the end hour means "always active",
 * which is a flat 24h.
 */
fun activeWindowLengthMillis(startHour: Int, endHour: Int): Long {
    if (startHour == endHour) return TWENTY_FOUR_HOURS_MILLIS
    val spanHours = if (endHour > startHour) endHour - startHour else 24 - startHour + endHour
    return spanHours * 60L * 60L * 1000L
}

/**
 * Fixed interval in millis until the next task should be added to the queue, or null when
 * "prompts per day" is 0 (nothing is ever dispatched). This is a plain fixed cadence, not a
 * window gate - the caller ([TaskDelivery.tick]) decides whether delivery should happen at all.
 *
 * When the previous tick actually dispatched a task, the remaining N-1 dispatches are spread
 * evenly across the *whole* window (windowLength / (N - 1)). When it skipped (queue was half or
 * more full), the next check is paced across the time *left* in the window (remainingWindow / N).
 * Either way the result is floored by [minDelayMillis] and capped at 24h.
 */
fun fixedDispatchIntervalMillis(
    now: LocalDateTime,
    startHour: Int,
    endHour: Int,
    promptsPerDay: Int,
    dispatched: Boolean
): Long? {
    if (promptsPerDay <= 0) return null
    // Only a real, currently-active window has a meaningful "close" to pace against - a manual
    // override running outside the configured window (for testing) has no such boundary, so it's
    // treated the same as an always-active window: a flat 24h.
    val remainingWindowMillis = if (isWithinActiveWindow(now, startHour, endHour)) {
        millisUntilWindowCloses(now, startHour, endHour) ?: TWENTY_FOUR_HOURS_MILLIS
    } else {
        TWENTY_FOUR_HOURS_MILLIS
    }
    val raw = if (dispatched) {
        activeWindowLengthMillis(startHour, endHour) / maxOf(promptsPerDay - 1, 1)
    } else {
        remainingWindowMillis / maxOf(promptsPerDay, 1)
    }
    return raw.coerceAtLeast(minDelayMillis(promptsPerDay)).coerceAtMost(TWENTY_FOUR_HOURS_MILLIS)
}

/**
 * The clean-day streak after a day boundary: a day with an abandon/timeout resets it to 0, a day
 * with at least one completion and no failure advances it, and a day with neither leaves it as-is
 * (so an idle day - e.g. "prompts per day" set to 0 - never breaks the streak).
 */
fun rolledCleanDayStreak(current: Int, hadCompletion: Boolean, hadFailure: Boolean): Int = when {
    hadFailure -> 0
    hadCompletion -> current + 1
    else -> current
}

fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private const val TEST_WEEK_WINDOW_MILLIS = 7 * 60_000L

/** How far back "this week" on the score screen looks - 7 real days, or 7 test minutes. */
fun weekScoreWindowMillis(promptsPerDay: Int): Long =
    if (isRapidTestingMode(promptsPerDay)) TEST_WEEK_WINDOW_MILLIS else Duration.ofDays(7).toMillis()

/**
 * How far back "this month" on the score screen looks - as many real days as [now]'s calendar
 * month actually has (28-31), or that same count in minutes when testing (e.g. a 31-day month
 * looks back 31 test minutes, not a flat 30) - the unit shrinks from days to minutes, but the
 * count stays tied to the real length of the current month either way.
 */
fun monthScoreWindowMillis(now: LocalDateTime, promptsPerDay: Int): Long {
    val monthLengthInDays = now.toLocalDate().lengthOfMonth().toLong()
    return if (isRapidTestingMode(promptsPerDay)) monthLengthInDays * 60_000L else Duration.ofDays(monthLengthInDays).toMillis()
}
