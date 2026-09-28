package com.example.myexodus.ui.theme

import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window-size type step: every role scales, lengths stay sp (the accessibility font scale
 *  must keep multiplying on top), 1.0f is the identity, and an em style is never re-labelled sp. */
class ScaledTypographyTest {

    @Test
    fun `every role scales and stays in sp`() {
        val factor = 1.25f
        val scaled = scaledTypography(Typography, factor)
        val roles = listOf(
            Typography.displayLarge to scaled.displayLarge,
            Typography.displayMedium to scaled.displayMedium,
            Typography.displaySmall to scaled.displaySmall,
            Typography.headlineLarge to scaled.headlineLarge,
            Typography.headlineMedium to scaled.headlineMedium,
            Typography.headlineSmall to scaled.headlineSmall,
            Typography.titleLarge to scaled.titleLarge,
            Typography.titleMedium to scaled.titleMedium,
            Typography.titleSmall to scaled.titleSmall,
            Typography.bodyLarge to scaled.bodyLarge,
            Typography.bodyMedium to scaled.bodyMedium,
            Typography.bodySmall to scaled.bodySmall,
            Typography.labelLarge to scaled.labelLarge,
            Typography.labelMedium to scaled.labelMedium,
            Typography.labelSmall to scaled.labelSmall,
        )
        roles.forEach { (base, wide) ->
            assertEquals(base.fontSize.value * factor, wide.fontSize.value, 0.001f)
            assertEquals(base.lineHeight.value * factor, wide.lineHeight.value, 0.001f)
            assertEquals(base.letterSpacing.value * factor, wide.letterSpacing.value, 0.001f)
            assertTrue(wide.fontSize.isSp)
            assertTrue(wide.lineHeight.isSp)
        }
    }

    @Test
    fun `factor one is the identity`() {
        val same = scaledTypography(Typography, 1f).bodyMedium
        assertEquals(14f, same.fontSize.value, 0.001f)
        assertEquals(20f, same.lineHeight.value, 0.001f)
    }

    @Test
    fun `em based lengths are never re-labelled as sp`() {
        val emBased = Typography.copy(bodyMedium = Typography.bodyMedium.copy(fontSize = 2.em))
        val scaled = scaledTypography(emBased, 1.25f).bodyMedium
        assertEquals(2f, scaled.fontSize.value, 0.001f)
        assertFalse(scaled.fontSize.isSp)
    }
}
