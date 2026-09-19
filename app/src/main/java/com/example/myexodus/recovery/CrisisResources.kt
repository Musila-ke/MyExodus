package com.example.myexodus.recovery

import org.json.JSONObject

/**
 * One crisis contact from `assets/crisis_resources.json` (plan v3 W6/W8). The list is
 * owner-maintained: [lastVerifiedAt] and [source] exist so staleness is visible on screen and
 * `CrisisResourcesReleaseTest` fails the build when an entry is unverifiable.
 */
data class CrisisResource(
    val id: String,
    val region: String,
    val name: String,
    val description: String,
    val phone: String,
    val sms: String,
    val url: String,
    val source: String,
    val lastVerifiedAt: String,
)

object CrisisResources {

    /** Region used by entries that apply everywhere. */
    const val GLOBAL_REGION = "GLOBAL"

    /**
     * Parses the asset. Malformed JSON returns an empty list rather than throwing — the crisis
     * screen must render something (its own "call emergency services" line) even if the
     * verified list is broken, and the release test is the guard that keeps that from shipping.
     */
    fun parse(json: String): List<CrisisResource> = runCatching {
        val array = JSONObject(json).getJSONArray("resources")
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            CrisisResource(
                id = entry.getString("id"),
                region = entry.getString("region"),
                name = entry.getString("name"),
                description = entry.optString("description"),
                phone = entry.optString("phone"),
                sms = entry.optString("sms"),
                url = entry.optString("url"),
                source = entry.optString("source"),
                lastVerifiedAt = entry.optString("lastVerifiedAt"),
            )
        }
    }.getOrDefault(emptyList())

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
}
