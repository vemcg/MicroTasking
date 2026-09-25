// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-24, after version v0.2.0-81 main 2026-09-24
package com.microtasking.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** What a scanned setup QR code carried: either value may be absent (see [parseSetupQr]). */
data class SetupQrPayload(val sheetUrl: String?, val webAppUrl: String?)

/**
 * Apps Script Web App URLs live on script.google.com (`/macros/s/<id>/exec`, or
 * `/a/macros/<domain>/s/<id>/exec` for Workspace accounts); a Google Sheet URL never does.
 */
fun looksLikeWebAppUrl(text: String): Boolean =
    text.startsWith("http", ignoreCase = true) && text.contains("script.google.com/", ignoreCase = true)

/**
 * Splits the onboarding page's QR text (one URL per line) into its sheet URL and Web App URL by
 * what each line looks like rather than by position, so a code carrying only the Web App URL, only
 * the sheet URL (older codes), or both in either order all work.
 */
fun parseSetupQr(scannedText: String): SetupQrPayload {
    val lines = scannedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return SetupQrPayload(
        sheetUrl = lines.firstOrNull { !looksLikeWebAppUrl(it) },
        webAppUrl = lines.firstOrNull { looksLikeWebAppUrl(it) }
    )
}

/**
 * The Web App answered, but with `ok:false`. [rowNotFound] is true when the answer means "that row
 * (or its tab) doesn't exist in the Sheet any more" - a definitive answer, not a transient failure
 * (see [isRowNotFound]); a queued write that gets it is dropped instead of retried forever.
 */
class WebAppException(message: String, val rowNotFound: Boolean) : Exception(message)

/**
 * Classifies an `ok:false` Web App answer as "row/tab not found". The v2 contract carries a `code`
 * (`no_such_row` / `no_such_tab`); the script actually deployed today (v1, no codes) only has the
 * error text - `No row with that task id`, `No row matching that description`,
 * `No tab named "<x>"` - so both are recognised.
 */
fun isRowNotFound(code: String?, message: String): Boolean =
    code == "no_such_row" || code == "no_such_tab" ||
        message.startsWith("No row ") || message.startsWith("No tab named ")

/**
 * Client for the per-user Apps Script Web App (see SPEC.md "Sheet connection & API (Apps Script
 * Web App)") - the only path this app uses to read or write the hidden Importance/Urgency columns.
 * Column A-C (checkbox/description/link) reads stay on the existing CSV/gviz path in
 * [importExternalTasksFromSheet]; this is deliberately separate.
 *
 * Row identity is `(category, description)` - the tab name and the task's description text - not
 * a row index, since rows shift under the bound script's delete-row-on-empty-description
 * behavior (see `onSheetEdit_` in scripts/populate_google_sheet.js). Matches this app's existing
 * `external-<category>-<description>` id convention.
 *
 * DEV (sheet-surrogate-keys): every call below also accepts/reads the Sheet's `taskId`
 * (`ensureTaskIds_`/`findRowByTaskId_` in the script) when one is available, preferring it over
 * `(category, description)` - the whole point being that a rename or a move to a different tab
 * doesn't change which row gets found. Falls back to the legacy text-based path automatically
 * whenever a task doesn't have one yet (a sheet not repaired to have the column).
 */
object WebAppClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** `"<category>|<description>"` - the legacy row-identity key, used when a task has no [ManagedTask.taskId] yet. */
    fun rowKey(category: String, description: String): String = "$category|$description"

    /**
     * Writes this task's referral (importance/urgency in `[0, 1]`) to its Sheet row. Called the
     * instant the Eisenhower touch is confirmed - on success the caller stamps
     * [ManagedTask.referredAt] locally right away rather than waiting for the next sync.
     *
     * [taskId] (DEV, sheet-surrogate-keys), when non-null, is sent alongside category/description
     * and takes priority server-side (see doPost's row resolution) - so the write still lands on
     * the right row even if category/description are stale from before a rename.
     */
    fun setPriority(
        webAppUrl: String, category: String, description: String, importance: Double, urgency: Double, taskId: String? = null
    ): Result<Unit> =
        post(
            webAppUrl,
            JSONObject().apply {
                put("action", "setPriority")
                put("category", category)
                put("description", description)
                put("importance", importance.coerceIn(0.0, 1.0))
                put("urgency", urgency.coerceIn(0.0, 1.0))
                if (taskId != null) put("taskId", taskId)
            }
        ).map { }

    /**
     * Clears a row's Importance/Urgency (ActiveTasks's "Complete (for now)" - included here for
     * completeness/testing even though this app's own UI doesn't trigger it; ActiveTasks calls the
     * same endpoint directly). [taskId]: see [setPriority].
     */
    fun clearPriority(webAppUrl: String, category: String, description: String, taskId: String? = null): Result<Unit> =
        post(
            webAppUrl,
            JSONObject().apply {
                put("action", "clearPriority")
                put("category", category)
                put("description", description)
                if (taskId != null) put("taskId", taskId)
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
                // DEV (sheet-surrogate-keys): prefer the row's taskId when the script reports one
                // (matches TaskPool.refreshReferralState's own preference on the local side); a
                // row from a sheet not yet repaired to have task ids reports it as JSON null, so
                // this falls back to the legacy text-based key exactly as before.
                val taskId = if (row.has("taskId") && !row.isNull("taskId")) row.getString("taskId") else null
                add(taskId ?: rowKey(row.getString("category"), row.getString("description")))
            }
        }
    }

    /**
     * Blank/non-http(s) URLs would otherwise surface as `MalformedURLException: no protocol: `,
     * which tells the user nothing - say what to actually do instead.
     */
    private fun requireWebAppUrl(webAppUrl: String): String {
        val trimmed = webAppUrl.trim()
        if (trimmed.isEmpty()) {
            error("No Web App URL is set yet. Add it in Settings (the \"Apps Script Web App URL\" field), then try again.")
        }
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            error("The Web App URL in Settings doesn't look right - it should start with https://script.google.com/...")
        }
        return trimmed
    }

    private fun post(webAppUrl: String, body: JSONObject): Result<JSONObject> = runCatching {
        val connection = (URL(requireWebAppUrl(webAppUrl)).openConnection() as HttpURLConnection).apply {
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
            val message = json.optString("error", "Web App returned ok=false")
            val code = if (json.has("code") && !json.isNull("code")) json.getString("code") else null
            throw WebAppException(message, rowNotFound = isRowNotFound(code, message))
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
