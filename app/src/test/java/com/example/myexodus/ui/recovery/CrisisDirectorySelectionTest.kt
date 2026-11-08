package com.example.myexodus.ui.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.recovery.CrisisResource
import com.example.myexodus.recovery.CrisisResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the screen leads with, against the shipped asset. The owner hit the failure this guards:
 * a US-locale phone sliced the directory to US entries, and the Urgent filter was empty because
 * the asset had no US emergency number at the time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrisisDirectorySelectionTest {

    private val resources: List<CrisisResource> by lazy {
        val json = ApplicationProvider.getApplicationContext<Context>()
            .assets.open(ASSET).use { it.readBytes().decodeToString() }
        CrisisResources.parse(json)
    }

    private fun slice(region: String) = CrisisResources.forRegion(resources, region)

    @Test
    fun `every covered region has a non-empty urgent filter`() {
        CrisisResources.regions(resources).forEach { region ->
            val urgent = slice(region).filter { CrisisResources.matches(it, CrisisResources.FILTER_URGENT) }
            assertFalse("$region slice must offer an emergency entry", urgent.isEmpty())
            assertTrue(
                "$region slice must put its emergency entry first",
                CrisisResources.sectionOf(urgent.first()) == CrisisResources.SECTION_URGENT,
            )
        }
    }

    @Test
    fun `the Kenya slice leads with the Red Cross hotline and dials 999`() {
        assertEquals("1199", heroEntry(slice("KE"), "KE")?.phone)
        assertEquals("999", emergencyEntry(slice("KE"))?.phone)
    }

    @Test
    fun `the US slice leads with 988 and dials 911`() {
        assertEquals("988", heroEntry(slice("US"), "US")?.phone)
        assertEquals("911", emergencyEntry(slice("US"))?.phone)
    }

    @Test
    fun `the all-regions view still has a hero and an emergency number`() {
        val all = slice(CrisisResources.REGION_ALL)
        assertEquals(resources.size, all.size)
        assertTrue(heroEntry(all, CrisisResources.REGION_ALL) != null)
        assertTrue(emergencyEntry(all)?.phone?.isNotBlank() == true)
    }
}
