// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-26, after version v0.2.0-86 main 2026-09-26
package com.microtasking.app

/**
 * Validation for the two connection URLs in Settings > Google Sheet Connection. Both apps show the
 * same thing under each field: nothing while it's blank, an error when it isn't the right kind of
 * URL, else a link whose text is just the URL's ID (the "hash") and whose target is the full URL.
 */
sealed interface UrlCheck {
    /** Nothing entered - no error, no link. */
    data object Empty : UrlCheck

    /** Something was entered but it isn't the right kind of Google URL (or carries no ID). */
    data object Invalid : UrlCheck

    /** [id] is the hash portion shown as the link text; [href] is the complete URL to open. */
    data class Valid(val id: String, val href: String) : UrlCheck
}

private val sheetIdRegex = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)")

// Consumer accounts: /macros/s/<id>/exec. Workspace accounts insert the domain: /a/macros/<domain>/s/<id>/exec.
private val webAppIdRegex = Regex("/macros/(?:[^/?#]+/)?s/([^/?#]+)")

fun sheetIdOf(url: String): String? = sheetIdRegex.find(url)?.groupValues?.getOrNull(1)

fun webAppIdOf(url: String): String? = webAppIdRegex.find(url)?.groupValues?.getOrNull(1)

private fun linkTarget(trimmed: String): String =
    if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"

fun checkSheetUrl(raw: String): UrlCheck {
    val url = raw.trim()
    if (url.isEmpty()) return UrlCheck.Empty
    if (!url.contains("docs.google.com/spreadsheets", ignoreCase = true)) return UrlCheck.Invalid
    val id = sheetIdOf(url) ?: return UrlCheck.Invalid
    return UrlCheck.Valid(id, linkTarget(url))
}

fun checkWebAppUrl(raw: String): UrlCheck {
    val url = raw.trim()
    if (url.isEmpty()) return UrlCheck.Empty
    val looksRight = url.contains("script.google.com/macros", ignoreCase = true) ||
        url.contains("script.google.com/a/macros", ignoreCase = true)
    if (!looksRight) return UrlCheck.Invalid
    val id = webAppIdOf(url) ?: return UrlCheck.Invalid
    return UrlCheck.Valid(id, linkTarget(url))
}
