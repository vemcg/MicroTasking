// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.microtasking.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Client for the per-user Apps Script Web App (see SPEC.md "Sheet write-back (Apps Script Web
 * App)") - the only path this app uses to read or write the hidden Importance/Urgency columns.
 * Column A-C (checkbox/description/link) reads stay on the existing CSV/gviz path in
 * [importExternalTasksFromSheet]; this is deliberately separate.
 *
 * Row identity is `(category, description)` - the tab name and the task's description text - not
 * a row index, since rows shift under the bound script's delete-row-on-empty-description
 * behavior (see `onSheetEdit_` in scripts/populate_google_sheet.js). Matches this app's existing
 * `external-<category>-<description>` id convention.
 */
object WebAppClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** `"<category>|<description>"` - the row-identity key used throughout this file and TaskPool.refreshReferralState. */
    fun rowKey(category: String, description: String): String = "$category|$description"

    /**
     * Writes this task's referral (importance/urgency in `[0, 1]`) to its Sheet row. Called the
     * instant the Eisenhower touch is confirmed - on success the caller stamps
     * [ManagedTask.referredAt] locally right away rather than waiting for the next sync.
     */
    fun setPriority(webAppUrl: String, category: String, description: String, importance: Double, urgency: Double): Result<Unit> =
        post(
            webAppUrl,
            JSONObject().apply {
                put("action", "setPriority")
                put("category", category)
                put("description", description)
                put("importance", importance.coerceIn(0.0, 1.0))
                put("urgency", urgency.coerceIn(0.0, 1.0))
            }
        ).map { }

    /**
     * Clears a row's Importance/Urgency (2do2go's "Complete (for now)" - included here for
     * completeness/testing even though this app's own UI doesn't trigger it; 2do2go calls the
     * same endpoint directly).
     */
    fun clearPriority(webAppUrl: String, category: String, description: String): Result<Unit> =
        post(
            webAppUrl,
            JSONObject().apply {
                put("action", "clearPriority")
                put("category", category)
                put("description", description)
            }
        ).map { }

    /**
     * Fetches every row currently carrying a non-empty Importance or Urgency, as row-identity
     * keys (see [rowKey]) - call only at existing sync boundaries (manual refresh + periodic
     * background sync), never from `TaskDelivery.tick`/selection, which must stay a fast
     * local-only read. Feed the result to [refreshReferralState].
     */
    fun getReferredRowKeys(webAppUrl: String): Result<Set<String>> = runCatching {
        val connection = (URL(appendQuery(webAppUrl, "action=getPriorities")).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val body = connection.readResponse()
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) {
            error(json.optString("error", "Web App returned ok=false"))
        }
        val rows = json.optJSONArray("rows") ?: JSONArray()
        buildSet {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                add(rowKey(row.getString("category"), row.getString("description")))
            }
        }
    }

    private fun post(webAppUrl: String, body: JSONObject): Result<JSONObject> = runCatching {
        val connection = (URL(webAppUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Apps Script Web Apps 302-redirect the first response to a script.googleusercontent.com
            // URL; HttpURLConnection follows GET redirects automatically but not POST ones (redirect
            // strips the body), so this must be retried as a GET-with-body-lost otherwise. Simplest
            // fix: let the connection follow it as a GET carrying the same query-string fallback -
            // Apps Script also accepts POST bodies on the redirected URL when instanceFollowRedirects
            // is left true and the redirect is same-origin-equivalent (script.google.com family), so
            // no special-casing needed here in practice.
            instanceFollowRedirects = true
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        val responseBody = connection.readResponse()
        val json = JSONObject(responseBody)
        if (!json.optBoolean("ok", false)) {
            error(json.optString("error", "Web App returned ok=false"))
        }
        json
    }

    private fun HttpURLConnection.readResponse(): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            ?: error("Web App call failed: HTTP $responseCode")
    }

    private fun appendQuery(url: String, query: String): String =
        if (url.contains("?")) "$url&$query" else "$url?$query"
}
