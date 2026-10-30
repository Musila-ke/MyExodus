package com.example.myexodus.backup

import com.nulabinc.zxcvbn.Zxcvbn

/**
 * Account plan §8.5/§17 gate 17: user-chosen passphrases need at least 16 characters and a
 * zxcvbn score of 4/4. The generated six-word option is accepted by its generation method —
 * zxcvbn would score dictionary words low even when 77 bits of uniform entropy were chosen
 * for it — so callers pass `generated = true` only for a passphrase this app just generated.
 * Local throttling is not treated as an offline-attack control (§8.5).
 */
object PassphrasePolicy {

    const val MIN_LENGTH = 16
    const val REQUIRED_SCORE = 4

    sealed interface Result {
        data object Accepted : Result
        data object TooShort : Result
        data class TooWeak(val score: Int) : Result
    }

    // zxcvbn builds its frequency lists on construction; one shared instance per process.
    private val zxcvbn by lazy { Zxcvbn() }

    fun check(passphrase: String, generated: Boolean = false): Result {
        if (passphrase.length < MIN_LENGTH) return Result.TooShort
        if (generated) return Result.Accepted
        val score = zxcvbn.measure(passphrase).score
        return if (score >= REQUIRED_SCORE) Result.Accepted else Result.TooWeak(score)
    }
}
