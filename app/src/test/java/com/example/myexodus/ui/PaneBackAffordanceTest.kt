package com.example.myexodus.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The subpage rule ("dense pages get subpages", owner ruling 2026-09-22) has a design half no test
 * can enforce — whether a split is worth making — and a mechanical half this file does: a pane
 * you can enter must offer the same way out. [BackRow] is the one definition; everything else
 * renders it. The enumeration that wrote this test found the two stragglers of 2026-09-27: the
 * rule editor's "gone" pane carried a bare TextButton (fixed), and the "coming soon" placeholder
 * turned out to be unreachable (deleted) — which is why the rule is enforced here rather than
 * trusted to hold.
 *
 * Granularity is per-file, the same as [SecureSurfacesTest]: a file that declares an `onBack:`
 * parameter anywhere must render a BackRow somewhere. A future file with two panes where only one
 * got a back row would slip past this test — the per-composable parse that would catch it costs
 * more than the failure mode is worth today.
 */
class PaneBackAffordanceTest {

    private fun uiSources(): List<File> {
        val uiDir = listOf(
            File("src/main/java/com/example/myexodus/ui"),
            File("app/src/main/java/com/example/myexodus/ui"),
        ).firstOrNull { it.isDirectory }
            ?: error("ui sources not found from ${File(".").absolutePath}")
        return uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `every composable taking onBack renders the shared BackRow`() {
        val declaresBack = Regex("""onBack\s*:""")

        val offenders = uiSources()
            .filter { it.name != "BackRow.kt" }
            .filter { declaresBack.containsMatchIn(it.readText()) }
            .filterNot { it.readText().contains("BackRow(") }
            .map { it.name }

        assertTrue(
            "A pane with an onBack parameter must render BackRow — the way out is the same " +
                "shape on every screen (ADR #90): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no screen outside BackRow hand-rolls the back affordance`() {
        val arrowOffenders = mutableListOf<String>()
        val labelOffenders = mutableListOf<String>()
        val literalOffenders = mutableListOf<String>()
        val backLiteral = Regex(""""Back"""")

        uiSources()
            .filter { it.name != "BackRow.kt" }
            .forEach { file ->
                file.readLines().withIndex().forEach { (index, line) ->
                    val at = "${file.name}:${index + 1}"
                    if ("ArrowBack" in line) arrowOffenders += at
                    if ("R.string.ui_back" in line) labelOffenders += at
                    if (backLiteral.containsMatchIn(line)) literalOffenders += at
                }
            }

        assertTrue(
            "The back icon lives in BackRow.kt only, so its RTL flip and description stay " +
                "fixed: $arrowOffenders",
            arrowOffenders.isEmpty(),
        )
        assertTrue(
            "Back labels come with the BackRow, not pulled from resources by hand: $labelOffenders",
            labelOffenders.isEmpty(),
        )
        assertTrue(
            "A hardcoded \"Back\" label would not translate and is a second affordance: " +
                "$literalOffenders",
            literalOffenders.isEmpty(),
        )
    }

    /**
     * The visible affordance is only half of "a pane you can enter has a way out" (owner
     * report 2026-09-28: the phone's back button exited the app from inside spokes). The
     * mechanical marker for a spoke host is its state-constant naming (`VIEW_`/`PANE_`/
     * `ROUTE_`); a file that owns spokes must register a `BackHandler` beside them. A spoke
     * added to a host that already has a handler is not caught here — same per-file ceiling
     * as the tests above, chosen for the same reason.
     */
    @Test
    fun `every screen owning subpage state handles the system back button`() {
        val declaresSpokes = Regex("""private const val (VIEW_|PANE_|ROUTE_)""")

        val offenders = uiSources()
            .filter { declaresSpokes.containsMatchIn(it.readText()) }
            .filterNot { it.readText().contains("BackHandler(") }
            .map { it.name }

        assertTrue(
            "A screen that owns subpage state must handle the system back button, or back " +
                "exits the app instead of popping the spoke: $offenders",
            offenders.isEmpty(),
        )
    }
}
