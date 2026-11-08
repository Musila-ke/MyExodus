package com.example.myexodus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NotchedNavBarGeometryTest {

    @Test
    fun `a zero-width measure clamps instead of crashing`() {
        // Device 2026-11-08: recreation measured maxWidth = 0, which put the upper bound at
        // -77 while the lower stayed 77. coerceIn threw and the process died — taking the
        // accessibility and enforcement services with it, so blocks silently lifted.
        assertEquals(77f, clampToRange(999f, 77f, -77f), 0f)
        assertEquals(77f, clampToRange(-999f, 77f, -77f), 0f)
    }

    @Test
    fun `a normal measure still clamps both sides`() {
        assertEquals(10f, clampToRange(-5f, 10f, 90f), 0f)
        assertEquals(90f, clampToRange(120f, 10f, 90f), 0f)
        assertEquals(40f, clampToRange(40f, 10f, 90f), 0f)
    }
}
