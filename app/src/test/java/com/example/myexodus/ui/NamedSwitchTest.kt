package com.example.myexodus.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Material `Switch` renders as its own accessibility node. Where it sits beside its label
 * rather than inside it — which is how every row in this app is built — the switch does not
 * inherit that label, so a screen reader announces it as a bare "On/Off". Twenty of them in a
 * list produce twenty identical announcements and no way to tell which row is which.
 *
 * Enforced mechanically rather than by review because the defect is invisible to sighted
 * testing: the screen looks and behaves correctly the whole time.
 *
 * `onClick = null` is the exception: it makes a control presentational (the row above it owns
 * the tap and carries the label), so those are not counted.
 */
class NamedSwitchTest {

    private val controls = listOf("Switch(", "Checkbox(", "RadioButton(")

    /** Balanced-paren slice of [text] starting at the control's own opening paren. */
    private fun callSite(text: String, openParen: Int): String {
        var depth = 0
        for (i in openParen until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParen, i + 1)
                }
            }
        }
        return text.substring(openParen)
    }

    @Test
    fun `every toggle control names itself`() {
        val uiDir = listOf(
            File("src/main/java/com/example/myexodus/ui"),
            File("app/src/main/java/com/example/myexodus/ui"),
        ).firstOrNull { it.isDirectory }
            ?: error("ui sources not found from ${File(".").absolutePath}")

        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                controls.flatMap { control ->
                    val found = mutableListOf<String>()
                    var at = text.indexOf(control)
                    while (at >= 0) {
                        val line = text.take(at).count { it == '\n' } + 1
                        val call = callSite(text, at + control.lastIndex)
                        if (!call.contains("contentDescription") && !call.contains("onClick = null")) {
                            found += "${file.name}:$line (${control.dropLast(1)})"
                        }
                        at = text.indexOf(control, at + control.length)
                    }
                    found
                }
            }
            .toList()

        assertTrue(
            "These toggle controls render without an accessible name; add " +
                "`modifier = Modifier.semantics { contentDescription = ... }`: $offenders",
            offenders.isEmpty(),
        )
    }
}
