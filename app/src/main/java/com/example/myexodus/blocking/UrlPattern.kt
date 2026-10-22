package com.example.myexodus.blocking

import java.net.IDN
import java.util.Locale

/**
 * Canonicalized URL restriction rule (Enhanced Mode spec §8 Step 8). The structural part
 * (scheme/port/host/path) is a defined transform — never raw address-bar text — and the
 * activation fields mirror [com.example.myexodus.data.db.entity.RestrictionRule] so the
 * decision engine applies the same V1 Rules 1–3 to websites as to apps.
 *
 * Canonicalization rules applied by [parseStructure] (shared by pattern and candidate):
 * host lowercased; punycode/IDN decoded to Unicode AND raw ASCII form both retained and
 * matched; default ports (80/443) stripped — including on scheme-less text, where the port
 * cannot distinguish anything the omnibox shows (it elides default ports), so a rule typed
 * as `example.com:80` must canon to what the address bar reads back; an explicit scheme keeps
 * a port that is non-default for it; userinfo (`user@`) stripped entirely; path retained only
 * when the rule explicitly includes a non-root path prefix; `http`/`https` equivalent unless
 * the rule specifies a scheme; `blob:`, `data:`, `javascript:` never canonicalized (always
 * allowed).
 *
 * Matching (see [UrlPattern.matches]): registrable-domain suffix with a dot boundary —
 * `example.com` matches `sub.example.com` but never `example.com.evil.com`. Linear scan over
 * the pattern list is the spec-approved structure: 2,000 patterns is single-digit
 * milliseconds (plan §8 Step 2).
 */
data class UrlPattern(
    val ruleId: String,
    val scheme: String?,
    val port: Int?,
    val hostUnicode: String,
    val hostAscii: String,
    val pathPrefix: String?,
    val isAlwaysOn: Boolean,
    val dailyLimitMs: Long,
    val timeRangeStart: Long?,
    val timeRangeEnd: Long?,
    val activeDaysOfWeek: String,
    val timezone: String = "UTC",
    val blockExpiresAt: Long,
    val severityLevel: String,
) {
    fun matches(candidate: CanonicalUrl): Boolean {
        // A scheme-less candidate (the address bar's normal display state — observed
        // "example.com" for http://example.com on Chrome/XOS 2026-08-27) states no scheme,
        // so it cannot violate the pattern's pin; only an explicit candidate scheme is
        // compared. Without this, any scheme-pinned rule could never match real address-bar
        // text and the URL-blocking path would be dead (caught on-device 2026-08-27).
        if (candidate.scheme != null && scheme != null && scheme != candidate.scheme) return false
        if (port != null && port != candidate.port) return false
        if (!hostMatches(candidate)) return false
        return pathMatches(candidate)
    }

    private fun hostMatches(candidate: CanonicalUrl): Boolean =
        candidate.hostUnicode == hostUnicode ||
            candidate.hostAscii == hostAscii ||
            candidate.hostUnicode.endsWith(".$hostUnicode") ||
            candidate.hostAscii.endsWith(".$hostAscii")

    private fun pathMatches(candidate: CanonicalUrl): Boolean {
        val prefix = pathPrefix ?: return true
        val candidatePath = candidate.pathPrefix ?: return false
        // Segment boundary, not raw prefix: "/forum" must not match "/forums".
        return candidatePath == prefix || candidatePath.startsWith("$prefix/")
    }

    companion object {
        /** Builds the canonical pattern from a stored rule's raw text; null when unparseable. */
        fun from(rule: com.example.myexodus.data.db.entity.RestrictionRule): UrlPattern? =
            parseStructure(rule.blockedPackage)?.let {
                UrlPattern(
                    ruleId = rule.ruleId,
                    scheme = it.scheme,
                    port = it.port,
                    hostUnicode = it.hostUnicode,
                    hostAscii = it.hostAscii,
                    pathPrefix = it.pathPrefix,
                    isAlwaysOn = rule.isAlwaysOn,
                    dailyLimitMs = rule.dailyLimitMs,
                    timeRangeStart = rule.timeRangeStart,
                    timeRangeEnd = rule.timeRangeEnd,
                    activeDaysOfWeek = rule.activeDaysOfWeek,
                    timezone = rule.timezone,
                    blockExpiresAt = rule.blockExpiresAt,
                    severityLevel = rule.severityLevel,
                )
            }
        /** Canonicalizes address-bar text for matching; null = no match (also blob:/data:/javascript:). */
        fun canonicalizeCandidate(raw: String): CanonicalUrl? =
            parseStructure(raw)?.let { CanonicalUrl(it.scheme, it.port, it.hostUnicode, it.hostAscii, it.pathPrefix) }

        fun parseStructure(raw: String): Structure? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase(Locale.ROOT)
            if (lower.startsWith("blob:") || lower.startsWith("data:") || lower.startsWith("javascript:")) {
                return null
            }
            val explicitScheme = explicitSchemeOf(trimmed)
            val rest = if (explicitScheme != null) trimmed.substring(explicitScheme.length + 3) else trimmed

            // Split authority from path/query/fragment at the first '/', '?' or '#'.
            val firstDelimiter = listOf('/', '?', '#').mapNotNull { c ->
                rest.indexOf(c).takeIf { it >= 0 }
            }.minOrNull()
            val authorityRaw = if (firstDelimiter == null) rest else rest.substring(0, firstDelimiter)
            val pathRaw = if (firstDelimiter == null) null else rest.substring(firstDelimiter)

            // Strip userinfo entirely (may contain ':' but never '@').
            val atIndex = authorityRaw.lastIndexOf('@')
            val authority = if (atIndex >= 0) authorityRaw.substring(atIndex + 1) else authorityRaw

            val host: String
            var port: Int? = null
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(0, close + 1)
                val after = authority.substring(close + 1)
                if (after.isNotEmpty()) {
                    if (!after.startsWith(":")) return null
                    port = after.substring(1).toIntOrNull() ?: return null
                }
            } else {
                val colon = authority.indexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    port = authority.substring(colon + 1).toIntOrNull() ?: return null
                } else {
                    host = authority
                }
            }
            if (host.isEmpty()) return null

            // A trailing dot is the DNS root label, and browsers drop it — an unmatchable
            // "example.com." rule would silently enforce nothing (device review 2026-10-22).
            val hostLower = host.lowercase(Locale.ROOT).removeSuffix(".")
            if (hostLower.isEmpty()) return null
            val ascii = runCatching { IDN.toASCII(hostLower) }.getOrDefault(hostLower)
            val unicode = runCatching { IDN.toUnicode(ascii) }.getOrDefault(hostLower)
            // Default ports are elided by the omnibox regardless of scheme, so scheme-less
            // 80/443 must canon away too — a rule typed as "example.com:80" that kept port 80
            // could never match the address-bar text the scanner reads back ("example.com").
            val normalizedPort = when {
                port == null -> null
                port == 80 && explicitScheme != "https" -> null
                port == 443 && explicitScheme != "http" -> null
                else -> port
            }
            val path = pathRaw?.substringBefore('?')?.substringBefore('#')
                ?.takeIf { it.isNotEmpty() && it != "/" }?.trimEnd('/')
            return Structure(if (explicitScheme == null) null else explicitScheme, normalizedPort, unicode, ascii, path)
        }

        private fun explicitSchemeOf(raw: String): String? {
            val idx = raw.indexOf("://")
            if (idx <= 0) return null
            return when (raw.substring(0, idx).lowercase(Locale.ROOT)) {
                "http" -> "http"
                "https" -> "https"
                else -> null
            }
        }
    }
}

/** Address-bar URL in canonicalized form, compared against stored [UrlPattern]s. */
data class CanonicalUrl(
    val scheme: String?,
    val port: Int?,
    val hostUnicode: String,
    val hostAscii: String,
    val pathPrefix: String?,
)

data class Structure(
    val scheme: String?,
    val port: Int?,
    val hostUnicode: String,
    val hostAscii: String,
    val pathPrefix: String?,
)
