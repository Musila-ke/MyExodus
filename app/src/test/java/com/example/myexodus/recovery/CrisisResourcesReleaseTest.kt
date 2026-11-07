package com.example.myexodus.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crisis list is the one surface in this app where a stale or broken entry is a safety
 * problem, so its freshness is enforced by the build, not by intent: an entry with no source,
 * no date, or a date older than a year fails the suite and has to be re-verified by hand
 * before the next release (`assets/crisis_resources.json` says exactly this).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrisisResourcesReleaseTest {

    private val resources: List<CrisisResource> by lazy {
        val json = ApplicationProvider.getApplicationContext<Context>()
            .assets.open("crisis_resources.json").use { it.readBytes().decodeToString() }
        CrisisResources.parse(json)
    }

    @Test
    fun theAssetParses_andIsNotEmpty() {
        assertTrue("the packaged list must parse and carry entries", resources.isNotEmpty())
    }

    @Test
    fun everyEntry_namesAVerifiableSource() {
        resources.forEach { resource ->
            assertTrue("${resource.id}: source is what makes the entry checkable", resource.source.isNotBlank())
            assertTrue("${resource.id}: name", resource.name.isNotBlank())
            assertTrue("${resource.id}: region (or GLOBAL)", resource.region.isNotBlank())
        }
    }

    @Test
    fun everyEntry_offersAChannelTheAppCanOpen() {
        resources.forEach { resource ->
            val channels = listOf(resource.phone, resource.sms, resource.whatsapp, resource.url)
                .filter { it.isNotBlank() }
            assertTrue("${resource.id}: at least one channel", channels.isNotEmpty())
            listOf(resource.phone, resource.sms, resource.whatsapp)
                .filter { it.isNotBlank() }
                .forEach { number ->
                    assertTrue(
                        "${resource.id}: a channel number must normalize to digits only",
                        CrisisChannels.digits(number) == number.removePrefix("+").replace("-", ""),
                    )
                }
            if (resource.url.isNotBlank()) {
                assertTrue("${resource.id}: url must be https", resource.url.startsWith("https://"))
            }
        }
    }

    @Test
    fun everyEntry_hasABehaviorAndKindFromTheAllowedSet() {
        resources.forEach { resource ->
            assertTrue(
                "${resource.id}: behaviors are the filter keys; one is required",
                resource.behaviors.isNotEmpty(),
            )
            assertTrue(
                "${resource.id}: unknown behavior ${resource.behaviors}",
                CrisisResources.BEHAVIORS.containsAll(resource.behaviors),
            )
            assertTrue(
                "${resource.id}: unknown kind '${resource.kind}'",
                resource.kind in CrisisResources.KINDS,
            )
        }
    }

    @Test
    fun kenyaRegion_doesNotContainUSEntries() {
        val forKenya = CrisisResources.forRegion(resources, "KE")

        assertTrue("a US service must stay under the US region", forKenya.none { it.region == "US" })
    }

    @Test
    fun kenyaRegion_putsEmergencyServicesFirst() {
        val forKenya = CrisisResources.forRegion(resources, "KE")

        assertEquals(CrisisResources.KIND_EMERGENCY, forKenya.first().kind)
        assertEquals(CrisisResources.SECTION_URGENT, CrisisResources.sectionOf(forKenya.first()))
    }

    @Test
    fun aMalformedEntry_isSkippedNotFatal() {
        val json = """
            {"resources":[
              {"id":"broken"},
              {"id":"ok","region":"KE","name":"Service","phone":"999",
               "behaviors":["emergency"],"kind":"emergency","lastVerifiedAt":"2026-11-07"}
            ]}
        """.trimIndent()

        assertEquals(listOf("ok"), CrisisResources.parse(json).map { it.id })
    }

    @Test
    fun everyEntry_wasVerifiedWithinTheLastYear() {
        val today = LocalDate.now()
        resources.forEach { resource ->
            val verified = try {
                LocalDate.parse(resource.lastVerifiedAt)
            } catch (e: DateTimeParseException) {
                throw AssertionError("${resource.id}: lastVerifiedAt must be an ISO date", e)
            }
            assertTrue(
                "${resource.id}: verification date ${resource.lastVerifiedAt} is in the future",
                !verified.isAfter(today),
            )
            assertTrue(
                "${resource.id}: verified ${resource.lastVerifiedAt} — re-check the number and bump the date",
                verified.isAfter(today.minusYears(1)),
            )
        }
    }

    @Test
    fun ids_areUnique() {
        val ids = resources.map { it.id }
        assertEquals("duplicate ids would shadow a row in the list", ids.size, ids.distinct().size)
    }

    @Test
    fun aGlobalEntry_coversRegionsWeHaveNotVerified() {
        val forNowhere = CrisisResources.forRegion(resources, "ZZ")

        assertTrue(
            "an unverified region must still see the worldwide directory",
            forNowhere.any { it.region == CrisisResources.GLOBAL_REGION },
        )
    }

    @Test
    fun forRegion_putsTheLocalEntriesFirst() {
        val forKenya = CrisisResources.forRegion(resources, "KE")

        assertTrue("a verified region sees its own entries", forKenya.any { it.region == "KE" })
        val firstGlobal = forKenya.indexOfFirst { it.region == CrisisResources.GLOBAL_REGION }
        val lastRegional = forKenya.indexOfLast { it.region == "KE" }
        assertTrue("local entries rank above the global ones", lastRegional < firstGlobal)
    }
}
