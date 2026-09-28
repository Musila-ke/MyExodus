package com.example.myexodus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two surfaces render the streak hero's label off the same habit: Home's card and the Streaks
 * summary card. They were near-identical copies, and they drifted — Home capped the label to one
 * line, Streaks did not — so a 95-character habit name wrapped to four lines on the Streaks tab
 * alone. That single uncapped label added ~157px, which is what pushed the page's only action
 * below the fold and left the "Had a slip?" card visibly amputated at rest (audit findings
 * #27/#28, ADR #85).
 *
 * The invariant is that the label has exactly one renderer and that renderer caps it. Enforced
 * mechanically rather than by review because the defect is invisible until a habit name is long
 * enough to wrap, which no reviewer types by hand.
 */
class HabitLabelGuardTest {

    private val uiDir = listOf(
        File("src/main/java/com/example/myexodus/ui"),
        File("app/src/main/java/com/example/myexodus/ui"),
    ).firstOrNull { it.isDirectory }
        ?: error("ui sources not found from ${File(".").absolutePath}")

    private val labelStrings = listOf(
        "R.string.streaks_days_free_from",
        "R.string.streaks_days_free",
    )

    /**
     * Passing the label *into* the shared row is the legal call — Home resolves the string and
     * hands it over. What must not happen is a surface rendering it under a `Text(` of its own,
     * which is how the cap was lost the first time. So each mention of these resources has to
     * belong to the nearest enclosing call open, and that call has to be `HabitLabelRow(`.
     */
    @Test
    fun `the streak label is only ever handed to the shared row`() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                labelStrings.flatMap { marker ->
                    val found = mutableListOf<String>()
                    var at = text.indexOf(marker)
                    while (at >= 0) {
                        val viaRow = text.lastIndexOf("HabitLabelRow(", at)
                        val viaText = text.lastIndexOf("Text(", at)
                        if (viaRow < 0 || viaText > viaRow) {
                            val line = text.take(at).count { it == '\n' } + 1
                            found += "${file.name}:$line ($marker)"
                        }
                        at = text.indexOf(marker, at + marker.length)
                    }
                    found
                }
            }
            .toList()

        assertTrue(
            "The streak label is rendered somewhere other than HabitLabelRow. The shared row is " +
                "what keeps the one-line cap on every surface — route these through it: $offenders",
            offenders.isEmpty(),
        )
    }

    /** One cap per branch: the row with the switch affordance, and the plain label beside it. */
    @Test
    fun `the shared row caps the label in both branches`() {
        val source = uiDir.walkTopDown()
            .filter { it.isFile && it.name == "StreakCards.kt" }
            .firstOrNull()
            ?.readText()
            ?: error("StreakCards.kt not found under ${uiDir.absolutePath}")

        val start = source.indexOf("internal fun HabitLabelRow")
        assertTrue("HabitLabelRow is gone from StreakCards.kt", start >= 0)
        val body = source.substring(start, source.indexOf("\n}\n", start).coerceAtLeast(start))

        assertEquals(
            "Both branches of HabitLabelRow must cap the label (maxLines + ellipsis): $body",
            2,
            Regex("maxLines = 1").findAll(body).count(),
        )
        assertEquals(2, Regex("TextOverflow\\.Ellipsis").findAll(body).count())
    }
}
