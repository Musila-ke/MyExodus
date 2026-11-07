package com.example.myexodus.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The channel-intent mapping (plan G3): the directory stores numbers in the form the official
 * source published them, and the screen needs a URI each target app accepts. Pure strings, so
 * this is checked without an Android runtime.
 */
class CrisisChannelsTest {

    @Test
    fun `a displayed number becomes a dialable tel uri`() {
        assertEquals("tel:+254722178177", CrisisChannels.telUri("+254 722 178 177"))
        assertEquals("tel:1199", CrisisChannels.telUri("1199"))
    }

    @Test
    fun `sms uses sendto with the normalized number`() {
        assertEquals("smsto:741741", CrisisChannels.smsUri("741741"))
        assertEquals("smsto:+254722178177", CrisisChannels.smsUri("+254 722 178 177"))
    }

    @Test
    fun `whatsapp uses wa me without a plus`() {
        assertEquals("https://wa.me/254700121121", CrisisChannels.whatsappUri("+254 700 121 121"))
    }

    @Test
    fun `a blank channel yields no uri rather than a broken one`() {
        assertNull(CrisisChannels.telUri(""))
        assertNull(CrisisChannels.smsUri(" "))
        assertNull(CrisisChannels.whatsappUri(""))
    }
}
