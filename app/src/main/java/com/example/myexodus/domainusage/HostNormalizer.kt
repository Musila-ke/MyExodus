package com.example.myexodus.domainusage

import java.net.IDN

/**
 * S2.1/S2.2: the single normalizer for the pattern keys this feature matches on. [normalize]
 * serves RuleEditor input (S9); [normalizeLegacy] serves stored rule values, discarding any
 * path/query/fragment instead of rejecting it.
 *
 * `_` in a label is rejected here. It is legal only on the SNI path, whose own validation
 * (S4.3) allows `[a-z0-9._-]`; no rule pattern can contain one, so an underscore SNI simply
 * matches no rule.
 *
 * A path-bearing input on an otherwise-invalid host reports the specific failure rather than
 * [Result.HasPath] — the suggested host is validated through the same pipeline, so the editor
 * never offers a suggestion the next save would reject.
 */
object HostNormalizer {

    sealed interface Result {
        /** Valid normalized pattern key (lowercase ASCII, no trailing dot). */
        data class Ok(val host: String) : Result

        /** Input had a path/query/fragment; [suggestedHost] is the validated "Use it" value. */
        data class HasPath(val suggestedHost: String) : Result

        data object IpLiteral : Result
        data object TooBroad : Result
        data object NeverBlock : Result
        data object EncryptedDnsResolver : Result
        data object Invalid : Result
    }

    fun normalize(input: String, psl: PublicSuffixList): Result = normalize(input, psl, pathIsError = true)

    /** Stored-rule variant: the same checks, with any path/query/fragment discarded. */
    fun normalizeLegacy(input: String, psl: PublicSuffixList): Result =
        normalize(input, psl, pathIsError = false)

    private fun normalize(input: String, psl: PublicSuffixList, pathIsError: Boolean): Result {
        val trimmed = input.trim().lowercase()
        if (trimmed.isEmpty()) return Result.Invalid
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")

        val cut = withoutScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authorityRaw = if (cut >= 0) withoutScheme.substring(0, cut) else withoutScheme
        val rest = if (cut >= 0) withoutScheme.substring(cut) else ""

        val at = authorityRaw.lastIndexOf('@')
        val authority = if (at >= 0) authorityRaw.substring(at + 1) else authorityRaw

        // Two or more colons cannot be a port; it is an IPv6 literal (or garbage).
        if (authority.count { it == ':' } >= 2) return Result.IpLiteral
        val colon = authority.lastIndexOf(':')
        val host = if (colon >= 0 && colon + 1 < authority.length && authority.substring(colon + 1).all { it.isDigit() }) {
            authority.substring(0, colon)
        } else {
            authority
        }

        val finished = finish(host, psl)
        if (!pathIsError || rest == "/" || rest.isEmpty()) return finished
        return if (finished is Result.Ok) Result.HasPath(finished.host) else finished
    }

    private fun finish(rawHost: String, psl: PublicSuffixList): Result {
        val host = rawHost.trimEnd('.')
        if (host.isEmpty()) return Result.Invalid
        val ascii = try {
            IDN.toASCII(host)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid
        }
        if (IPV4.matches(ascii)) return Result.IpLiteral
        if (ascii.length > 253) return Result.Invalid
        val labels = ascii.split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return Result.Invalid
            if (label.startsWith("-") || label.endsWith("-")) return Result.Invalid
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return Result.Invalid
        }
        if (labels.size < 2) return Result.TooBroad
        if (psl.isPublicSuffix(ascii)) return Result.TooBroad
        if (NeverBlockDomains.matches(ascii)) return Result.NeverBlock
        if (EncryptedDnsResolvers.matches(ascii)) return Result.EncryptedDnsResolver
        return Result.Ok(ascii)
    }

    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
}
