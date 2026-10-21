package com.example.myexodus.ui.rules

import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RestrictionRule
import java.util.UUID

/**
 * Owner request 2026-10-21: the empty Rules page has to let a user block a whole category
 * without visiting a single triggering site to copy its address. Each list is curated here,
 * reviewed in the sheet, and only added on an explicit tap.
 *
 * A preference (one host per line, all lowercase, bare registrable domain) so the catalog can
 * be spot-checked and diffed; the catalog's uniqueness and dedupe tests pin the rest.
 */
internal data class WebsiteCategory(
    val id: String,
    val titleRes: Int,
    val sites: List<String>,
)

internal object WebsitePresets {

    val categories: List<WebsiteCategory> = listOf(
        WebsiteCategory(
            id = "social",
            titleRes = R.string.rules_preset_social,
            sites = listOf(
                "facebook.com", "instagram.com", "x.com", "tiktok.com", "snapchat.com",
                "reddit.com", "pinterest.com", "linkedin.com", "tumblr.com", "threads.net",
            ),
        ),
        WebsiteCategory(
            id = "adult",
            titleRes = R.string.rules_preset_adult,
            sites = listOf(
                "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
                "youporn.com", "onlyfans.com", "chaturbate.com", "stripchat.com",
                "bongacams.com",
            ),
        ),
        WebsiteCategory(
            id = "betting",
            titleRes = R.string.rules_preset_betting,
            sites = listOf(
                "bet365.com", "draftkings.com", "fanduel.com", "betway.com",
                "williamhill.com", "betfair.com", "paddypower.com", "unibet.com",
                "1xbet.com", "pointsbet.com",
            ),
        ),
        WebsiteCategory(
            id = "video",
            titleRes = R.string.rules_preset_video,
            sites = listOf(
                "youtube.com", "netflix.com", "twitch.tv", "hulu.com", "disneyplus.com",
                "primevideo.com", "max.com", "kick.com", "vimeo.com", "dailymotion.com",
            ),
        ),
        WebsiteCategory(
            id = "news",
            titleRes = R.string.rules_preset_news,
            sites = listOf(
                "cnn.com", "bbc.com", "nytimes.com", "theguardian.com", "foxnews.com",
                "dailymail.co.uk", "buzzfeed.com", "huffpost.com", "reuters.com",
                "apnews.com",
            ),
        ),
        WebsiteCategory(
            id = "shopping",
            titleRes = R.string.rules_preset_shopping,
            sites = listOf(
                "amazon.com", "ebay.com", "etsy.com", "temu.com", "shein.com",
                "aliexpress.com", "walmart.com", "target.com", "asos.com", "wish.com",
            ),
        ),
    )

    fun category(id: String): WebsiteCategory? = categories.firstOrNull { it.id == id }
}

/**
 * The sites of [category] this profile does not already have a rule for. Dedupe is against
 * **every** stored website rule, paused or expired included: the table's unique
 * (profile_id, blocked_package) index counts them, so a "paused" duplicate would throw at
 * insert rather than add a second row.
 */
internal fun unblockedSites(
    category: WebsiteCategory,
    existing: List<RestrictionRule>,
): List<String> {
    val taken = existing.asSequence()
        .filter { it.targetType == "WEBSITE" }
        .map { targetKey(it.blockedPackage, "WEBSITE") }
        .toSet()
    return category.sites.filterNot { targetKey(it, "WEBSITE") in taken }
}

/**
 * A preset site becomes a plain block-only rule — "Always on" with no window and no limit —
 * which is what the category lists promise ("auto-blockable"). Days are all seven and the
 * timezone is the device's, matching a rule created through the editor's All-the-time mode.
 */
internal fun websitePresetRule(profileId: String, host: String, timezone: String): RestrictionRule =
    RestrictionRule(
        ruleId = UUID.randomUUID().toString(),
        profileId = profileId,
        blockedPackage = host,
        targetType = "WEBSITE",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        timezone = timezone,
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )
