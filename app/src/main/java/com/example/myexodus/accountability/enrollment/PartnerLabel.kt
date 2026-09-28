package com.example.myexodus.accountability.enrollment

/**
 * §19.5 item 3: the human label exchanged during pairing so the multi-user switcher can name
 * each supported user. Plan decision (flagged for veto, not vetoed): each side's self-chosen
 * name, sanitized to ≤32 characters, rides the [EnrollmentPayload]; absent/blank falls back to
 * the key-id fragment the dashboard already shows, never to a fabricated name.
 *
 * Sanitizing is a trust boundary: the label arrives from the partner's device and is rendered
 * on this one, so control/format characters (bidi overrides included) are dropped rather than
 * displayed — whitespace-class ones (tab, newline) become plain spaces instead of vanishing,
 * since dropping them fuses words — and the length cap counts code points, so an emoji is one
 * character, not two.
 */
object PartnerLabel {

    const val MAX_CODE_POINTS = 32

    /** Null when nothing usable remains — the caller falls back, it never invents a label. */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        // Whitespace first: a tab or newline inside a pasted name is a separator, and dropping
        // it outright would fuse "Ana\nMaria" into "AnaMaria" for every partner to read.
        val cleaned = raw.map { if (it.isWhitespace()) ' ' else it }
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .joinToString("")
            .trim()
            .replace(WHITESPACE_RUN, " ")
        if (cleaned.isEmpty()) return null
        val points = cleaned.codePoints().limit((MAX_CODE_POINTS + 1).toLong()).toArray()
        return if (points.size <= MAX_CODE_POINTS) {
            cleaned
        } else {
            String(points, 0, MAX_CODE_POINTS)
        }
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
