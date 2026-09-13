package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The blocking overlay's pause card (V1 State 2): a URL block must name the site and carry
 *  the Interception Escape Hatch; an app block (ADR #31) shows the block screen's card twin —
 *  the app named, the same single recovery action, no different-site action. */
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
            (0 until row.childCount).mapNotNull { (row.getChildAt(it) as? TextView)?.text?.toString() }
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
}
