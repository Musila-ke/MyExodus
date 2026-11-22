package com.example.myexodus.accountability.sos

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import com.example.myexodus.ui.SosConfirmDialog
import com.example.myexodus.ui.partner.ReachOutTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOS plan §12.3 rev (owner mandate 2026-10-04, V1 Req 35; owner ruling 2026-10-23; owner
 * report 2026-11-22 #3): the reach-out confirmation on the real render path — it names the
 * partner, offers an explicit recipient when several are live (one per send, never fan-out),
 * shows the fixed check-in request it will send, and has no free-text field (custom text is
 * How They Are Doing's Send message).
 *
 * Rendering the extracted [SosConfirmDialog] in isolation, the same contract style as
 * [NudgeHomeSurfaceInstrumentedTest]: this leg pins the dialog surface on-device; the tap →
 * envelope path is pinned by SosViewModelTest/NudgeEnvelopeTest (JVM) and the wire contract
 * by NudgeRepositoryTest.
 */
@RunWith(AndroidJUnit4::class)
class NudgeDialogInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Sent(val message: String?, val pairId: String?)

    private val sent = mutableListOf<Sent>()
    private var dismissed = 0

    private fun str(resId: Int, vararg args: Any): String = compose.activity.getString(resId, *args)

    private fun render(targets: List<ReachOutTarget>, initialPairId: String? = targets.firstOrNull()?.pairId) {
        compose.setContent {
            SosConfirmDialog(
                targets = targets,
                initialPairId = initialPairId,
                onSend = { message, pairId -> sent += Sent(message, pairId) },
                onDismiss = { dismissed++ },
            )
        }
    }

    private fun partner(name: String, pairId: String = "pair-${name.lowercase()}") =
        ReachOutTarget(pairId = pairId, name = name)

    @Test
    fun dialog_namesThePartner_showsTheFixedCheckIn_andSendsIt() {
        render(listOf(partner("Sam")))

        compose.onNodeWithText(str(R.string.sos_confirm_title_named, "Sam")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sos_default_message)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.sos_confirm_send)).performClick()
        compose.waitForIdle()
        assertEquals(
            "the fixed check-in message and the single partner travel together",
            listOf(Sent(str(R.string.sos_default_message), "pair-sam")),
            sent,
        )
        assertEquals(0, dismissed)
    }

    @Test
    fun unnamedTarget_fallsBackToTheGenericTitle_andStillSendsTheFixedCheckIn() {
        render(targets = emptyList(), initialPairId = null)

        compose.onNodeWithText(str(R.string.sos_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sos_target_label)).assertDoesNotExist()

        compose.onNodeWithText(str(R.string.sos_confirm_send)).performClick()
        compose.waitForIdle()
        assertEquals(
            "the fixed message travels; no pick = default target",
            listOf(Sent(str(R.string.sos_default_message), null)),
            sent,
        )
    }

    @Test
    fun multiplePartners_pickOneRecipient_andTheTitleFollowsThePick() {
        render(listOf(partner("Sam"), partner("Alex")))

        compose.onNodeWithText(str(R.string.sos_target_label)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sos_confirm_title_named, "Sam")).assertIsDisplayed()

        compose.onNodeWithText("Alex").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.sos_confirm_title_named, "Alex")).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.sos_confirm_send)).performClick()
        compose.waitForIdle()
        assertEquals(
            "the chosen recipient is the one on the wire — never both",
            listOf(Sent(str(R.string.sos_default_message), "pair-alex")),
            sent,
        )
    }

    @Test
    fun cancel_dismissesWithoutSending() {
        render(listOf(partner("Sam")))

        compose.onNodeWithText(str(R.string.sos_confirm_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(emptyList<Sent>(), sent)
    }
}
