package com.microtasking.app

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

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
 * Floor on the delay nextPromptDelayMillis can return. Without this, opening the app very late
 * in the active window with many prompts still due makes remainingWindowMillis / remainingPrompts
 * round down toward zero, which used to fire a rapid-fire burst of deliveries (each one doing
 * disk I/O and posting a notification) tight enough to ANR the app.
 */
private const val MIN_DELAY_MILLIS = 30_000L

/**
 * Delay in millis until the next task should be added to the queue, or null if nothing more
 * should be delivered until the next window occurrence (quota reached, prompts disabled, or too
 * little time is left in the window to safely deliver anything else today).
 * Spreads the remaining quota unevenly across the remaining window time rather than on a fixed beat.
 */
fun nextPromptDelayMillis(
    now: LocalDateTime,
    startHour: Int,
    endHour: Int,
    promptsPerDay: Int,
    promptsDeliveredInWindow: Int,
    random: Random = Random.Default
): Long? {
    if (promptsPerDay <= 0 || promptsDeliveredInWindow >= promptsPerDay) return null
    if (!isWithinActiveWindow(now, startHour, endHour)) {
        return millisUntilWindowOpens(now, startHour, endHour)
    }
    val remainingPrompts = promptsPerDay - promptsDeliveredInWindow
    val remainingWindowMillis = millisUntilWindowCloses(now, startHour, endHour)
        ?: Duration.ofHours(24).toMillis()
    if (remainingWindowMillis < MIN_DELAY_MILLIS) return null
    val averageInterval = (remainingWindowMillis / remainingPrompts).coerceAtLeast(MIN_DELAY_MILLIS)
    val low = (averageInterval / 2).coerceAtLeast(MIN_DELAY_MILLIS)
    val high = (averageInterval + averageInterval / 2).coerceAtLeast(low + 1L)
    return (low + random.nextLong(high - low)).coerceAtMost(remainingWindowMillis)
}

fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
