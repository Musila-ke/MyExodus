package com.example.myexodus.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * User-visible copy is a resource, never a Kotlin literal: a literal cannot be translated and
 * sails through review because it looks finished in English. The 2026-09-27 shell sweep
 * (ADR #91) found the class alive in four places the earlier per-page audits missed — the
 * shell's pre-database screen and tab labels, Home's empty/CTA cards, and three notification
 * builders — so the rule is enforced here rather than trusted.
 *
 * Matched against raw file text (not line-by-line), so a literal opened on the line after
 * `Text(` is caught too. Glyph-only and format-only literals (`"•"`, `"${n}/${m}"`) never match
 * because the character after the quote must be an ASCII letter.
 */
class LiteralCopyGuardTest {

    private fun mainSources(): List<File> {
        val root = listOf(
            File("src/main/java/com/example/myexodus"),
            File("app/src/main/java/com/example/myexodus"),
        ).firstOrNull { it.isDirectory }
            ?: error("main sources not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `user-visible copy is never a Kotlin literal`() {
        val patterns = listOf(
            "Text(" to Regex("""\bText\(\s*"[A-Za-z]"""),
            "notification text" to
                Regex("""\b(setContentTitle|setContentText|setSubText)\(\s*"[A-Za-z]"""),
            // The codebase's convention for copy passed into a builder (`DegradedCopy`).
            "copy constructor" to Regex("""\b\w*Copy\(\s*"[A-Za-z]"""),
            // Channel names are user-visible in system notification settings; the channel
            // constructor's second argument is the name, whatever the surrounding call shape.
            "channel name" to Regex("""\bNotificationChannel\(\s*[^,]+,\s*"[A-Za-z]"""),
        )
        val offenders = mutableListOf<String>()
        mainSources()
            .forEach { file ->
                patterns.forEach { (kind, pattern) ->
                    if (pattern.containsMatchIn(file.readText())) offenders += "${file.name} ($kind)"
                }
            }

        assertTrue(
            "User-visible copy must come from strings.xml — a literal cannot be translated: " +
                offenders,
            offenders.isEmpty(),
        )
    }
}
