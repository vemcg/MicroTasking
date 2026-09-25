// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-84 main 2026-09-25
package com.microtasking.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short-TTL memory of task rows this device just removed via an incoming "fullyCompleted" event
 * (ActiveTasks deleted the Sheet row), used to keep a stale/lagged Sheet CSV read from resurrecting
 * the row for a sync or two (found jointly with ActiveTasks, 2026-09-25 - DEFECTS.md item 8; their
 * TaskStore.kt has the mirror-image `recentCompletions` guard for the same race in the other
 * direction). `mergeImportedManagedTasks` merges purely by id against whatever the CSV read just
 * said, with no memory of a deletion that already landed locally - Google's gviz/CSV export can lag
 * behind a script-driven edit, so a sync that happens to catch the stale cache re-adds the row until
 * a fresher one removes it again.
 *
 * Keyed the same way referral matching already is ([referralKeyOf]): taskId when the row has one,
 * else "category|description".
 */
data class RemovedTaskMarker(val key: String, val removedAtEpochMs: Long)

// Comfortably covers gviz's typical CSV-cache lag with margin - not tied to any measured worst
// case, just "long enough that a resurrected row won't outlive it, short enough that a task
// genuinely re-added under the same key within minutes isn't held back."
const val recentlyRemovedTaskTtlMs = 10 * 60 * 1000L

fun pruneExpiredRemovals(
    markers: List<RemovedTaskMarker>, now: Long, ttlMs: Long = recentlyRemovedTaskTtlMs
): List<RemovedTaskMarker> = markers.filter { now - it.removedAtEpochMs < ttlMs }

/** Drops any [imported] row that was removed here very recently - a stale Sheet CSV read must not resurrect it. */
fun filterResurrectedRows(
    imported: List<ManagedTask>, removed: List<RemovedTaskMarker>, now: Long, ttlMs: Long = recentlyRemovedTaskTtlMs
): List<ManagedTask> {
    val activeKeys = pruneExpiredRemovals(removed, now, ttlMs).map { it.key }.toSet()
    if (activeKeys.isEmpty()) return imported
    return imported.filterNot { referralKeyOf(it) in activeKeys }
}

fun readRecentlyRemoved(json: String): List<RemovedTaskMarker> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val entry = values.getJSONObject(index)
        RemovedTaskMarker(key = entry.getString("key"), removedAtEpochMs = entry.getLong("removedAtEpochMs"))
    }
}.getOrDefault(emptyList())

fun writeRecentlyRemoved(markers: List<RemovedTaskMarker>): String = JSONArray().apply {
    markers.forEach { marker ->
        put(JSONObject().apply {
            put("key", marker.key)
            put("removedAtEpochMs", marker.removedAtEpochMs)
        })
    }
}.toString()

object RecentlyRemovedTasks {
    const val PREFS_KEY = "recently_removed_tasks"

    /** Records [key] as just-removed, pruning anything already expired at the same time. */
    fun markRemoved(context: Context, key: String, now: Long = System.currentTimeMillis()) {
        val prefs = TaskDelivery.prefs(context)
        val current = readRecentlyRemoved(prefs.getString(PREFS_KEY, "[]") ?: "[]")
        val updated = pruneExpiredRemovals(current, now) + RemovedTaskMarker(key, now)
        prefs.edit().putString(PREFS_KEY, writeRecentlyRemoved(updated)).apply()
    }

    /** The still-active markers, persisting the pruned set back if anything expired. */
    fun current(context: Context, now: Long = System.currentTimeMillis()): List<RemovedTaskMarker> {
        val prefs = TaskDelivery.prefs(context)
        val markers = readRecentlyRemoved(prefs.getString(PREFS_KEY, "[]") ?: "[]")
        val pruned = pruneExpiredRemovals(markers, now)
        if (pruned.size != markers.size) {
            prefs.edit().putString(PREFS_KEY, writeRecentlyRemoved(pruned)).apply()
        }
        return pruned
    }
}
