package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The blocking overlay's pause card (V1 State 2): a URL block must name the site and carry
 *  the Interception Escape Hatch; an app block (ADR #31) shows the block screen's card twin —
 *  the app named, the same single recovery action, no different-site action — and is
 *  persistent like the daily wall (owner ruling 2026-10-03): on an evicting host it carries
 *  the in-place Close and stays until the user dismisses it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlockOverlayViewTest {

    private lateinit var context: Context
    private val recovered = mutableListOf<String>()
    private val differentSite = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun view() = BlockOverlayView(context, {}, recovered::add, differentSite::add)

    private fun buttons(v: BlockOverlayView): List<Button> =
        (0 until v.childCount).flatMap { i ->
            val row = v.getChildAt(i) as ViewGroup
            (0 until row.childCount).mapNotNull { row.getChildAt(it) as? Button }
        }

    private fun texts(v: BlockOverlayView): List<String> =
        (0 until v.childCount).flatMap { i ->
            val row = v.getChildAt(i) as ViewGroup
            (0 until row.childCount).mapNotNull {
                val text = row.getChildAt(it) as? TextView ?: return@mapNotNull null
                text.text?.toString()?.takeIf { text.visibility == View.VISIBLE }
            }
        }

    @Test
    fun urlBlock_showsSiteAndEscapeHatch_actionsCarryRuleAndBrowser() {
        val v = view()
        v.bind(OverlayContent.UrlBlocked("example.com", "rule-1", "com.android.chrome"))

        val labels = buttons(v).filter { it.visibility == View.VISIBLE }
        assertEquals(2, labels.size)
        assertEquals(context.getString(R.string.overlay_open_recovery), labels[0].text)
        assertEquals(context.getString(R.string.overlay_different_site), labels[1].text)

        labels[0].performClick()
        assertEquals(listOf("rule-1"), recovered)
        labels[1].performClick()
        assertEquals(listOf("com.android.chrome"), differentSite)
    }

    @Test
    fun appBlock_showsTargetAndRecoveryOnly_rebindSwapsActions() {
        val v = view()
        v.bind(OverlayContent.UrlBlocked("example.com", "rule-1", "com.android.chrome"))
        v.bind(OverlayContent.AppBlocked("AnkiDroid", "rule-2"))

        assertTrue(texts(v).contains(context.getString(R.string.blocked_target_title, "AnkiDroid")))
        val visible = buttons(v).filter { it.visibility == View.VISIBLE }
        assertEquals(1, visible.size)
        assertEquals(context.getString(R.string.overlay_open_recovery), visible[0].text)
        visible[0].performClick()
        assertEquals(listOf("rule-2"), recovered)
        val different = buttons(v).first { it.text == context.getString(R.string.overlay_different_site) }
        assertEquals("stale different-site action must not survive the rebind", View.GONE, different.visibility)
    }

    @Test
    fun appBlock_withoutRule_statesTheBlockWithoutAnAction() {
        val v = view()
        v.bind(OverlayContent.AppBlocked("AnkiDroid", ruleId = null))

        assertTrue(buttons(v).all { it.visibility == View.GONE })
        assertTrue(texts(v).contains(context.getString(R.string.blocked_target_title, "AnkiDroid")))
    }

    // --- daily-limit content (enforcement plan v2 §8 Step 10) ---

    @Test
    fun dailyLimitBlock_withoutAnEvictingHost_hasNoActions_showsTheHintOnly() {
        val v = view()
        // Uninstalled package: appLabel falls back to the package string.
        v.bind(OverlayContent.DailyLimitBlocked("com.instagram.android"))

        assertTrue(texts(v).any { it.contains("com.instagram.android") })
        assertTrue(texts(v).contains(context.getString(R.string.limit_blocked_message)))
        // No Close here: this host cannot evict, so an in-place dismiss would hand the
        // blocked app straight back (OverlayController.dismissWall).
        assertTrue(buttons(v).all { it.visibility == View.GONE })
        // v1 pause-screen parity (user-directed 2026-09-15): the block screen's own hint.
        // Never the cover's transient variant.
        assertTrue(texts(v).contains(context.getString(R.string.block_screen_hint)))
        assertTrue(texts(v).none { it == context.getString(R.string.overlay_hint) })
    }

    @Test
    fun dailyWall_closeAction_dismissesAndNeverSurvivesIntoAUrlCard() {
        var dismissed = 0
        val wall = BlockOverlayView(
            context, {}, recovered::add, differentSite::add, onWallDismiss = { dismissed++ },
        )
        wall.bind(OverlayContent.DailyLimitBlocked("com.instagram.android"))
        val close = buttons(wall).first { it.text == context.getString(R.string.overlay_wall_dismiss) }
        assertEquals(View.VISIBLE, close.visibility)
        close.performClick()
        assertEquals(1, dismissed)

        // The app-block card runs the same persistent contract (owner ruling 2026-10-03):
        // its Close stays armed across the rebind and dismisses too.
        wall.bind(OverlayContent.AppBlocked("AnkiDroid", "rule-2"))
        assertEquals(View.VISIBLE, close.visibility)
        close.performClick()
        assertEquals(2, dismissed)

        // A URL card offers no in-place dismiss (a stray tap would hand the blocked page
        // straight back from a cover the user has no way to bring back) — the Close must
        // not survive into it.
        wall.bind(OverlayContent.UrlBlocked("example.com", "rule-1", "com.android.chrome"))
        assertEquals(View.GONE, close.visibility)
    }

    @Test
    fun appBlock_onAnEvictingHost_showsRecoveryAndClose_withTheWallHint() {
        var dismissed = 0
        val host = BlockOverlayView(
            context, {}, recovered::add, differentSite::add, onWallDismiss = { dismissed++ },
        )
        host.bind(OverlayContent.AppBlocked("AnkiDroid", "rule-2"))

        val visible = buttons(host).filter { it.visibility == View.VISIBLE }
        assertEquals(2, visible.size)
        assertEquals(context.getString(R.string.overlay_open_recovery), visible[0].text)
        assertEquals(context.getString(R.string.overlay_wall_dismiss), visible[1].text)
        visible[0].performClick()
        assertEquals(listOf("rule-2"), recovered)
        visible[1].performClick()
        assertEquals(1, dismissed)

        // Wall parity (owner ruling 2026-10-03): the card is a wall now — the block screen's
        // hint, never the cover's transient "Home and back always work".
        assertTrue(texts(host).contains(context.getString(R.string.block_screen_hint)))
        assertTrue(texts(host).none { it == context.getString(R.string.overlay_hint) })
    }

    @Test
    fun appBlock_usesTheSharedWallRhythm_withTheComposeTypeRoles() {
        // Task #4 audit: this card and BlockerActivity.BlockScreen are one composition —
        // disc 24 title 8 message 32 recovery 8 Close 24 hint, 32dp inset — so the a11y
        // cover cannot restyle when the durable screen takes over (compose rhythm in
        // BlockerActivity, values here).
        val host = BlockOverlayView(
            context, {}, recovered::add, differentSite::add, onWallDismiss = {},
        )
        host.bind(OverlayContent.AppBlocked("AnkiDroid", "rule-2"))
        val column = host.getChildAt(0) as ViewGroup
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        assertEquals("32dp inset, as the block screen's Column", px(32), column.paddingStart)
        val title = column.getChildAt(1) as TextView
        val message = column.getChildAt(2) as TextView
        val close = column.getChildAt(5) as TextView
        val hint = column.getChildAt(6) as TextView
        assertEquals("message follows the title by 8dp", px(8), (message.layoutParams as LinearLayout.LayoutParams).topMargin)
        assertEquals("message → recovery gap", px(32), (message.layoutParams as LinearLayout.LayoutParams).bottomMargin)
        assertEquals("recovery → Close gap", px(8), (column.getChildAt(3).layoutParams as LinearLayout.LayoutParams).bottomMargin)
        assertEquals("Close → hint gap", px(24), (close.layoutParams as LinearLayout.LayoutParams).bottomMargin)
        assertEquals(px(0), (hint.layoutParams as LinearLayout.LayoutParams).topMargin)

        // The Compose text roles: Work Sans (SansFamily), no font padding (Compose has none).
        // The weight axis (fontVariationSettings) is set alongside but does not round-trip
        // through the getter under Robolectric's native-graphics stack; the rendered weight
        // is verified on device, not here.
        val workSans = ResourcesCompat.getFont(context, R.font.work_sans)
        assertNotNull("Work Sans must load from res/font", workSans)
        assertEquals("the card's typeface is SansFamily, not the themed default", workSans, title.typeface)
        assertEquals(workSans, message.typeface)
        assertEquals(workSans, hint.typeface)
        assertTrue("Compose has no font padding", !title.includeFontPadding && !message.includeFontPadding && !hint.includeFontPadding)
    }

    @Test
    fun warning_showsCeilingOfRemainingMinutes_perLevel() {
        val v = view()
        // 4m05s left reads "about 5 min", never "4 min".
        v.bind(OverlayContent.Warning("com.instagram.android", level = 1, remainingMs = 4 * 60_000L + 5_000L))
        assertTrue(texts(v).contains(context.getString(R.string.limit_warning_title, 5)))
        assertTrue(buttons(v).all { it.visibility == View.GONE })

        v.bind(OverlayContent.Warning("com.instagram.android", level = 2, remainingMs = 60_000L))
        assertTrue(texts(v).contains(context.getString(R.string.limit_warning_title_final, 1)))
    }

    @Test
    fun warning_usesTheInsetCard_notTheWallColumn() {
        val v = view()
        v.bind(OverlayContent.Warning("com.instagram.android", level = 1, remainingMs = 60_000L))
        val column = v.getChildAt(0) as ViewGroup
        val card = v.getChildAt(1) as ViewGroup
        assertEquals(View.GONE, column.visibility)
        assertEquals(View.VISIBLE, card.visibility)

        v.bind(OverlayContent.DailyLimitBlocked("com.instagram.android"))
        assertEquals("the wall column must come back", View.VISIBLE, column.visibility)
        assertEquals("the warning card must not survive into a block", View.GONE, card.visibility)
        // The title must come back too: a one-way GONE on the banner path left the reused
        // view's wall title hidden through the rebind (device-caught 2026-09-27 — the wall
        // rendered without "… is done for today" after any banner).
        assertEquals(
            View.VISIBLE,
            (column.getChildAt(1) as TextView).visibility,
        )
    }

    @Test
    fun tapDismissesBannersOnly() {
        var dismissed = 0
        val banner = BlockOverlayView(context, {}, recovered::add, differentSite::add) { dismissed++ }
        banner.bind(OverlayContent.Warning("com.instagram.android", level = 1, remainingMs = 60_000L))
        banner.performClick()
        assertEquals(1, dismissed)

        banner.bind(OverlayContent.DailyLimitBlocked("com.instagram.android"))
        banner.performClick()
        assertEquals("a block card must not dismiss on tap", 1, dismissed)
    }

    @Test
    fun attachConfirm_isNeverAssumedAtBindTime() {
        val confirmed = mutableListOf<Unit>()
        val v = view()
        v.bind(OverlayContent.DailyLimitBlocked("com.instagram.android"), onAttached = { confirmed.add(Unit) })
        // Detached view: the callback waits for the attach pre-draw, it does not fire early
        // (the §8 Step 4 eviction must never precede the card being on screen).
        assertTrue(confirmed.isEmpty())
    }
}
