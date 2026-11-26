package com.example.myexodus.accountability.serialization

/**
 * One rule's block schedule as the partner surfaces state it (OPD #48): mechanism, day scope,
 * daily cap and end date. The partner reads this instead of only "rule updated".
 *
 * [encode] is the single-line form carried inside the E2EE rule-change payload and stored in
 * the journal/projection columns. Every field is numeric or a flags mask, so no free text
 * enters it and the closed body needs no sanitizing. Window minutes are wall-clock minutes of
 * the sender's rule, rendered as-is on the receiver (the same values the owner's own rule
 * card shows).
 */
data class RuleSchedule(
    val alwaysOn: Boolean,
    /** Monday-first 7-char mask (bit0 = Monday) — the shape both rule editors write. */
    val days: String,
    val windowStartMinute: Long?,
    val windowEndMinute: Long?,
    /** 0 = no daily cap. */
    val dailyLimitMs: Long,
    /** Epoch millis; 0 = never expires. */
    val expiresAtMs: Long,
) {
    init {
        require(days.length == 7 && days.all { it == '0' || it == '1' }) {
            "days is not a seven-day mask"
        }
        require((windowStartMinute == null) == (windowEndMinute == null)) {
            "a window needs both ends"
        }
        windowStartMinute?.let {
            require(it in 0..MAX_MINUTE_OF_DAY) { "window start is not a wall-clock minute" }
        }
        windowEndMinute?.let {
            require(it in 0..MAX_MINUTE_OF_DAY) { "window end is not a wall-clock minute" }
        }
        require(dailyLimitMs >= 0) { "daily limit is negative" }
        require(expiresAtMs >= 0) { "expiry is negative" }
    }

    /** Blocking mechanism as one axis: always-on and the window are the same setting to a
     *  reader ("all day" vs "9:00–5:00"), so they change together or not at all. */
    fun changedAxesFrom(before: RuleSchedule): Set<Axis> = buildSet {
        if (alwaysOn != before.alwaysOn ||
            windowStartMinute != before.windowStartMinute ||
            windowEndMinute != before.windowEndMinute
        ) {
            add(Axis.BLOCKING)
        }
        if (days != before.days) add(Axis.DAYS)
        if (dailyLimitMs != before.dailyLimitMs) add(Axis.LIMIT)
        if (expiresAtMs != before.expiresAtMs) add(Axis.ENDS)
    }

    fun encode(): String = listOf(
        FORMAT_VERSION.toString(),
        if (alwaysOn) "1" else "0",
        days,
        windowStartMinute?.toString().orEmpty(),
        windowEndMinute?.toString().orEmpty(),
        dailyLimitMs.toString(),
        expiresAtMs.toString(),
    ).joinToString(SEPARATOR)

    enum class Axis { BLOCKING, DAYS, LIMIT, ENDS }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val SEPARATOR = "|"
        private const val MAX_ENCODED_CHARS = 96
        const val MAX_MINUTE_OF_DAY = 1439L

        private val DAYS_MASK = Regex("[01]{7}")

        /** Null = not this format (wrong field count/version/type/range). Never throws. */
        fun decodeOrNull(value: String): RuleSchedule? {
            if (value.length > MAX_ENCODED_CHARS) return null
            val parts = value.split(SEPARATOR)
            if (parts.size != 7 || parts[0] != FORMAT_VERSION.toString()) return null
            val alwaysOn = when (parts[1]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            if (!DAYS_MASK.matches(parts[2])) return null
            val start: Long?
            val end: Long?
            if (parts[3].isEmpty() && parts[4].isEmpty()) {
                start = null
                end = null
            } else {
                start = parts[3].toLongOrNull()?.takeIf { it in 0..MAX_MINUTE_OF_DAY }
                    ?: return null
                end = parts[4].toLongOrNull()?.takeIf { it in 0..MAX_MINUTE_OF_DAY }
                    ?: return null
            }
            val limit = parts[5].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val expires = parts[6].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return runCatching {
                RuleSchedule(alwaysOn, parts[2], start, end, limit, expires)
            }.getOrNull()
        }
    }
}
