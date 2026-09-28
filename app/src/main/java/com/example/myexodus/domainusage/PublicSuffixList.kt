package com.example.myexodus.domainusage

import android.content.Context

/**
 * S2.3: the pinned PSL asset (S1.6), checked so a rule can't be created on a bare public
 * suffix (`co.ke`). Loaded synchronously with [fromAsset] during Engine/application
 * initialization — not lazily on first RuleEditor save, whose validation needs a synchronous
 * answer (§16 item 28).
 *
 * Lookup follows the PSL algorithm's three rule kinds: an exception rule (`!name`) makes the
 * name *not* a public suffix even if a wildcard would say otherwise; an exact rule matches the
 * name itself; a wildcard rule `*.p` matches a name of exactly one extra label before `p`.
 */
class PublicSuffixList private constructor(
    private val exact: Set<String>,
    private val wildcardParents: Set<String>,
    private val exceptions: Set<String>,
) {

    fun isPublicSuffix(name: String): Boolean {
        val n = name.lowercase().trimEnd('.')
        if (n.isEmpty()) return false
        if (n in exceptions) return false
        if (n in exact) return true
        val dot = n.indexOf('.')
        return dot > 0 && n.substring(dot + 1) in wildcardParents
    }

    companion object {
        const val ASSET_NAME = "public_suffix_list.dat"

        /** Synchronous load of the pinned asset; call during initialization (§16 item 28). */
        fun fromAsset(context: Context): PublicSuffixList =
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readLines()) }

        fun parse(lines: Iterable<String>): PublicSuffixList {
            val exact = HashSet<String>()
            val wildcardParents = HashSet<String>()
            val exceptions = HashSet<String>()
            for (raw in lines) {
                val line = raw.substringBefore("//").trim().lowercase()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("!") -> exceptions += line.removePrefix("!")
                    line.startsWith("*.") -> wildcardParents += line.removePrefix("*.")
                    else -> exact += line
                }
            }
            return PublicSuffixList(exact, wildcardParents, exceptions)
        }
    }
}
