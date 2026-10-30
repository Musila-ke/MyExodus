package com.example.myexodus.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Account plan §8.5/§17 gate 17: 16-character minimum and zxcvbn 4/4 for user-chosen
 * passphrases; the generated six-word option is accepted by its generation method.
 */
class PassphrasePolicyTest {

    private val strong = "7Gx!q2-Lm9@Rv4#Tz8"

    @Test
    fun acceptsStrongPassphrase() {
        assertEquals(PassphrasePolicy.Result.Accepted, PassphrasePolicy.check(strong))
    }

    @Test
    fun rejectsShortPassphraseBeforeScoring() {
        assertEquals(PassphrasePolicy.Result.TooShort, PassphrasePolicy.check("Ab1!"))
        assertEquals(
            PassphrasePolicy.Result.TooShort,
            PassphrasePolicy.check("a".repeat(PassphrasePolicy.MIN_LENGTH - 1)),
        )
        assertEquals(PassphrasePolicy.Result.TooShort, PassphrasePolicy.check(""))
    }

    @Test
    fun sixteenCharactersIsNotTooShort() {
        val exactly16 = "q7#Xm2!vL9@rT4\$k"
        assertEquals(16, exactly16.length)
        assertEquals(PassphrasePolicy.Result.Accepted, PassphrasePolicy.check(exactly16))
    }

    @Test
    fun rejectsWeakLongPassphrase() {
        assertEquals(PassphrasePolicy.Result.TooWeak(0), PassphrasePolicy.check("passwordpassword"))
        assertEquals(PassphrasePolicy.Result.TooWeak(0), PassphrasePolicy.check("aaaaaaaaaaaaaaaa"))
    }

    @Test
    fun generatedPassphraseIsAcceptedByGenerationMethod() {
        val generated = "abacus abdomen abdominal abide abiding abject"
        assertEquals(
            PassphrasePolicy.Result.Accepted,
            PassphrasePolicy.check(generated, generated = true),
        )
        // The generated flag never bypasses the length floor.
        assertEquals(
            PassphrasePolicy.Result.TooShort,
            PassphrasePolicy.check("abide abject", generated = true),
        )
    }
}
