package com.example.myexodus.recovery

/**
 * The partner-mirror text convention for recovery sessions (W5, OPEN-3 default):
 * `recovery:<techniqueId>:<outcome>` carried on the shipped `TYPE_ACTIVITY` event — no rules
 * change, no new event type. Coarse by construction: technique id + terminal state, nothing
 * the user typed (INV-13). The parser lives in `ui/partner/PartnerPresentation` and delegates
 * here, so the format has exactly one definition.
 */
object RecoveryMirror {

    const val PREFIX = "recovery:"

    /** Terminal states only — a STARTED row is never mirrored (the drain query excludes it). */
    val OUTCOMES = setOf("completed", "abandoned", "interrupted")

    fun text(technique: String, state: String): String = "$PREFIX$technique:${state.lowercase()}"

    /** Returns the technique and outcome, or null when [text] is not a recovery mirror line. */
    fun parse(text: String): Pair<String, String>? {
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        val (technique, outcome) = parts
        if (technique.isEmpty() || outcome !in OUTCOMES) return null
        return technique to outcome
    }
}
