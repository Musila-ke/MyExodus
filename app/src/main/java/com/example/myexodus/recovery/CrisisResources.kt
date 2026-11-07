package com.example.myexodus.recovery

import org.json.JSONObject

/**
 * One crisis contact from `assets/crisis_resources.json` (plan v3 W6/W8; directory rebuild
 * plan G). The list is owner-maintained: [source] and [lastVerifiedAt] exist so staleness is
 * visible on screen and `CrisisResourcesReleaseTest` fails the build when an entry is
 * unverifiable.
 *
 * Everything after [source] is optional in the asset. A field the official source did not
 * state stays empty and the screen omits the row — it is never filled with a guess.
 */
data class CrisisResource(
    val id: String,
    val region: String,
    val name: String,
    val description: String,
    val phone: String,
    val sms: String,
    val whatsapp: String,
    val url: String,
    val source: String,
    val behaviors: List<String>,
    val kind: String,
    val hours: String,
    val cost: String,
    val eligibility: String,
    val limitations: String,
    val lastVerifiedAt: String,
)

object CrisisResources {

    /** Region used by entries that apply everywhere. */
    const val GLOBAL_REGION = "GLOBAL"

    const val KIND_HELPLINE = "helpline"
    const val KIND_FELLOWSHIP = "fellowship"
    const val KIND_REGULATOR = "regulator"
    const val KIND_HOSPITAL = "hospital"
    const val KIND_DIRECTORY = "directory"
    const val KIND_EMERGENCY = "emergency"

    val KINDS = setOf(
        KIND_HELPLINE,
        KIND_FELLOWSHIP,
        KIND_REGULATOR,
        KIND_HOSPITAL,
        KIND_DIRECTORY,
        KIND_EMERGENCY,
    )

    const val BEHAVIOR_MENTAL_HEALTH = "mental_health"
    const val BEHAVIOR_GAMBLING = "gambling"
    const val BEHAVIOR_PORN = "porn"
    const val BEHAVIOR_GAMING = "gaming"
    const val BEHAVIOR_SUBSTANCE = "substance"
    const val BEHAVIOR_PROFESSIONAL = "professional"
    const val BEHAVIOR_EMERGENCY = "emergency"

    val BEHAVIORS = setOf(
        BEHAVIOR_MENTAL_HEALTH,
        BEHAVIOR_GAMBLING,
        BEHAVIOR_PORN,
        BEHAVIOR_GAMING,
        BEHAVIOR_SUBSTANCE,
        BEHAVIOR_PROFESSIONAL,
        BEHAVIOR_EMERGENCY,
    )

    const val FILTER_ALL = "all"
    const val FILTER_URGENT = "urgent"
    const val FILTER_GAMBLING = BEHAVIOR_GAMBLING
    const val FILTER_PORN = BEHAVIOR_PORN
    const val FILTER_GAMING = BEHAVIOR_GAMING
    const val FILTER_MENTAL_HEALTH = BEHAVIOR_MENTAL_HEALTH

    val FILTERS = listOf(
        FILTER_ALL,
        FILTER_URGENT,
        FILTER_GAMBLING,
        FILTER_PORN,
        FILTER_GAMING,
        FILTER_MENTAL_HEALTH,
    )

    const val SECTION_URGENT = "urgent"
    const val SECTION_MENTAL_HEALTH = "mental_health"
    const val SECTION_GAMBLING = "gambling"
    const val SECTION_PORN = "porn"
    const val SECTION_GAMING = "gaming"
    const val SECTION_SUBSTANCE = "substance"
    const val SECTION_PROFESSIONAL = "professional"
    const val SECTION_MORE = "more"

    /** Display order, Kenya-first ordering already applied inside each section by [forRegion]. */
    val SECTION_ORDER = listOf(
        SECTION_URGENT,
        SECTION_MENTAL_HEALTH,
        SECTION_GAMBLING,
        SECTION_PORN,
        SECTION_GAMING,
        SECTION_SUBSTANCE,
        SECTION_PROFESSIONAL,
        SECTION_MORE,
    )

    /**
     * Parses the asset. A malformed entry is skipped rather than taking the whole list down —
     * the crisis screen must render something (its own "call emergency services" line) even if
     * one entry is broken, and the release test is the guard that keeps that from shipping.
     */
    fun parse(json: String): List<CrisisResource> = runCatching {
        val array = JSONObject(json).getJSONArray("resources")
        (0 until array.length()).mapNotNull { index ->
            runCatching { parseEntry(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseEntry(entry: JSONObject): CrisisResource {
        val behaviors = entry.optJSONArray("behaviors")
        return CrisisResource(
            id = entry.getString("id"),
            region = entry.getString("region"),
            name = entry.getString("name"),
            description = entry.optString("description"),
            phone = entry.optString("phone"),
            sms = entry.optString("sms"),
            whatsapp = entry.optString("whatsapp"),
            url = entry.optString("url"),
            source = entry.optString("source"),
            behaviors = if (behaviors == null) {
                emptyList()
            } else {
                (0 until behaviors.length()).mapNotNull { i ->
                    behaviors.optString(i).takeIf { it.isNotBlank() }
                }
            },
            kind = entry.optString("kind"),
            hours = entry.optString("hours"),
            cost = entry.optString("cost"),
            eligibility = entry.optString("eligibility"),
            limitations = entry.optString("limitations"),
            lastVerifiedAt = entry.optString("lastVerifiedAt"),
        )
    }

    /**
     * The device region's entries first, then the global ones — a user abroad still sees the
     * worldwide directory, and a user in a region we have not verified keeps that directory
     * rather than an empty screen.
     */
    fun forRegion(resources: List<CrisisResource>, regionCode: String): List<CrisisResource> {
        val regional = resources.filter { it.region.equals(regionCode, ignoreCase = true) }
        val global = resources.filter { it.region == GLOBAL_REGION }
        return regional + global
    }

    /**
     * The section an entry belongs to. One section per entry, fixed order. A professional
     * directory (KCPA/KPsyA) reads as Professional help, not as the generic directory row;
     * Find A Helpline has a mental-health label but is the directory of directories.
     */
    fun sectionOf(resource: CrisisResource): String = when {
        resource.kind == KIND_EMERGENCY || BEHAVIOR_EMERGENCY in resource.behaviors -> SECTION_URGENT
        BEHAVIOR_PROFESSIONAL in resource.behaviors -> SECTION_PROFESSIONAL
        BEHAVIOR_GAMBLING in resource.behaviors -> SECTION_GAMBLING
        BEHAVIOR_PORN in resource.behaviors -> SECTION_PORN
        BEHAVIOR_GAMING in resource.behaviors -> SECTION_GAMING
        BEHAVIOR_SUBSTANCE in resource.behaviors -> SECTION_SUBSTANCE
        resource.kind == KIND_DIRECTORY -> SECTION_MORE
        else -> SECTION_MENTAL_HEALTH
    }

    fun matches(resource: CrisisResource, filter: String): Boolean = when (filter) {
        FILTER_ALL -> true
        FILTER_URGENT -> resource.kind == KIND_EMERGENCY || BEHAVIOR_EMERGENCY in resource.behaviors
        else -> filter in resource.behaviors
    }

    /** The assist category preselects the matching directory filter (plan E2.10). */
    fun filterForCategory(category: UrgeCategory?): String = when (category) {
        UrgeCategory.PORN -> FILTER_PORN
        UrgeCategory.GAMBLING -> FILTER_GAMBLING
        UrgeCategory.GAMING -> FILTER_GAMING
        else -> FILTER_ALL
    }
}
