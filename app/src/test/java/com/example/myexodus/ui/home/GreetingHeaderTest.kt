package com.example.myexodus.ui.home

import com.example.myexodus.R
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Home header's anchor: which greeting each hour picks, and that the hour read is the
 *  device's own (a UTC read would greet the wrong half of the day). */
class GreetingHeaderTest {

    @Test
    fun `every hour of the day is covered by exactly one greeting`() {
        assertEquals(R.string.home_greeting_evening, greetingResId(0))
        assertEquals(R.string.home_greeting_evening, greetingResId(4))
        assertEquals(R.string.home_greeting_morning, greetingResId(5))
        assertEquals(R.string.home_greeting_morning, greetingResId(11))
        assertEquals(R.string.home_greeting_afternoon, greetingResId(12))
        assertEquals(R.string.home_greeting_afternoon, greetingResId(17))
        assertEquals(R.string.home_greeting_evening, greetingResId(18))
        assertEquals(R.string.home_greeting_evening, greetingResId(23))
    }

    @Test
    fun `hour of day is read in the device zone`() {
        val zone = ZoneId.systemDefault()
        val ninePm = LocalDateTime.of(2026, 9, 22, 21, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(21, hourOfDay(ninePm))
    }
}
