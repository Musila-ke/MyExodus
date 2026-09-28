package com.example.myexodus.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The window-size type step (theme/Theme.kt → scaledTypography) only reaches text that uses the
 * theme's typography roles. A literal `fontSize = 13.sp` opts one screen out of it silently —
 * including screens that do not exist yet — so the rule is enforced mechanically: sizes and
 * their line heights live in ui/theme/Type.kt, every other surface reads a role.
 */
class NoLiteralTypeSizeTest {

    private val literalSize = Regex("""(fontSize|lineHeight)\s*=\s*[\d.]+\s*\.\s*(sp|em)""")

    @Test
    fun `no ui source outside the type scale declares a literal font size`() {
        val uiDir = listOf(
            File("src/main/java/com/example/myexodus/ui"),
            File("app/src/main/java/com/example/myexodus/ui"),
        ).firstOrNull { it.isDirectory }
            ?: error("ui sources not found from ${File(".").absolutePath}")

        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.parentFile?.name != "theme" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { literalSize.containsMatchIn(it.value) }
                    .map { "${file.name}:${it.index + 1}" }
            }
            .toList()

        assertTrue(
            "Literal type sizes outside ui/theme/Type.kt escape the window-size step: $offenders",
            offenders.isEmpty(),
        )
    }
}
