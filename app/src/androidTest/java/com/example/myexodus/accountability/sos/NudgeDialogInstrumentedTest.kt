package com.example.myexodus.accountability.sos

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import com.example.myexodus.ui.SosConfirmDialog
import com.example.myexodus.ui.partner.ReachOutTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOS plan §12.3 rev (owner mandate 2026-10-04, V1 Req 35; owner ruling 2026-10-23): the
 * reach-out confirmation on the real render path — it names the partner, offers an explicit
 * recipient when several are live (one per send, never fan-out), prefills the default
 * message, sends the edited text, and a cleared field sends the v1 no-text nudge (null).
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
    fun dialog_namesThePartner_prefillsTheDefault_andSendsTheEditedText() {
        render(listOf(partner("Sam")))

        compose.onNodeWithText(str(R.string.sos_confirm_title_named, "Sam")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sos_default_message)).assertIsDisplayed()
        assertEquals(
            str(R.string.sos_default_message).length.toString() + "/${NudgeEnvelope.MAX_TEXT_CHARS}",
            str(
                R.string.sos_message_counter,
                str(R.string.sos_default_message).length,
                NudgeEnvelope.MAX_TEXT_CHARS,
            ),
        )

        compose.onNodeWithText(str(R.string.sos_default_message)).performTextReplacement("Please call me")
        compose.onNodeWithText(str(R.string.sos_confirm_send)).performClick()
        compose.waitForIdle()
        assertEquals(
            "the edited text and the single partner travel together",
            listOf(Sent("Please call me", "pair-sam")),
            sent,
        )
        assertEquals(0, dismissed)
    }

    @Test
    fun unnamedTarget_fallsBackToTheGenericTitle_andAClearedFieldSendsNoText() {
        render(targets = emptyList(), initialPairId = null)

        compose.onNodeWithText(str(R.string.sos_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sos_target_label)).assertDoesNotExist()

        compose.onNodeWithText(str(R.string.sos_default_message)).performTextClearance()
        compose.onNodeWithText(str(R.string.sos_confirm_send)).performClick()
        compose.waitForIdle()
        assertEquals(
            "blank must travel as null (v1 constant), never an empty string; no pick = default target",
            listOf(Sent(null, null)),
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
