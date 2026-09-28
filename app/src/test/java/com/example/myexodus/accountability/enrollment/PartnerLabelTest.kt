package com.example.myexodus.accountability.enrollment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §19.5 item 3: the label is a trust boundary — it arrives from the partner's device and is
 *  rendered on this one, so sanitizing is pinned here rather than assumed at the call sites. */
class PartnerLabelTest {

    @Test
    fun missingOrBlankLabels_stayNull_theCallerFallsBackRatherThanShowingEmpty() {
        assertNull(PartnerLabel.sanitize(null))
        assertNull(PartnerLabel.sanitize(""))
        assertNull(PartnerLabel.sanitize("   "))
        assertNull(PartnerLabel.sanitize(" ‮ ")) // a bidi override alone is not a name
    }

    @Test
    fun controlAndFormatCharacters_areDropped_notDisplayed() {
        // U+202E (RIGHT-TO-LEFT OVERRIDE) reorders rendered text — the classic spoofing vector
        // for a name shown in someone else's switcher. Non-whitespace controls go the same way.
        assertEquals("AnaMaria", PartnerLabel.sanitize("Ana‮Maria"))
        assertEquals("ab", PartnerLabel.sanitize("a\u0000b")) // NUL is not whitespace: dropped
        assertEquals("Ana", PartnerLabel.sanitize("A‏n⁦a⁩"))
    }

    @Test
    fun surroundingAndRepeatedWhitespace_collapsesToSingleSpaces() {
        assertEquals("Ana Maria", PartnerLabel.sanitize("  Ana \t  Maria  "))
        // Whitespace controls separate rather than vanish: fusing them would render a pasted
        // two-line name as one word.
        assertEquals("Ana Maria", PartnerLabel.sanitize("Ana\nMaria"))
        assertEquals("Ana Maria", PartnerLabel.sanitize("Ana\r\nMaria"))
    }

    @Test
    fun labelsAtTheCap_surviveUnchanged_andLongerOnesTruncate() {
        val exact = "a".repeat(PartnerLabel.MAX_CODE_POINTS)
        assertEquals(exact, PartnerLabel.sanitize(exact))

        assertEquals(exact, PartnerLabel.sanitize("a".repeat(PartnerLabel.MAX_CODE_POINTS + 1)))
    }

    @Test
    fun theCapCountsCodePoints_notChars_soAnEmojiIsOneCharacter() {
        // 33 emoji (surrogate pairs in UTF-16) must truncate to 32 code points — a char-count
        // cap would split a surrogate pair and render a broken glyph.
        val emoji = "🌿" // two UTF-16 units, one code point
        val sanitized = PartnerLabel.sanitize(emoji.repeat(PartnerLabel.MAX_CODE_POINTS + 1))!!
        assertEquals(PartnerLabel.MAX_CODE_POINTS, sanitized.codePointCount(0, sanitized.length))
        assertEquals(emoji.repeat(PartnerLabel.MAX_CODE_POINTS), sanitized)
    }

    @Test
    fun truncation_leavesNoUnpairedSurrogate() {
        // "a" + 32 emoji: the cut lands between pairs, and the result must stay well-formed.
        val emoji = "🌿"
        val sanitized = PartnerLabel.sanitize("a" + emoji.repeat(PartnerLabel.MAX_CODE_POINTS))!!
        assertEquals(PartnerLabel.MAX_CODE_POINTS, sanitized.codePointCount(0, sanitized.length))
        assertEquals("a" + emoji.repeat(PartnerLabel.MAX_CODE_POINTS - 1), sanitized)
    }
}
