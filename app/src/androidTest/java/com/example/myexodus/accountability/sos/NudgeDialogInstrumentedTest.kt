package com.example.myexodus.accountability.sos

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import com.example.myexodus.ui.SosConfirmDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOS plan §12.3 rev (owner mandate 2026-10-04, V1 Req 35): the reach-out confirmation dialog
 * on the real render path — it names the §19.5 target partner, prefills the default message,
 * sends the edited text, and a cleared field sends the v1 no-text nudge (null, not "").
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

    private val sent = mutableListOf<String?>()
    private var dismissed = 0

    private fun str(resId: Int, vararg args: Any): String = compose.activity.getString(resId, *args)

    private fun render(targetName: String?) {
        compose.setContent {
            SosConfirmDialog(
                targetName = targetName,
                onSend = { sent += it },
                onDismiss = { dismissed++ },
            )
        }
    }

    @Test
    fun dialog_namesThePartner_prefillsTheDefault_andSendsTheEditedText() {
        render(targetName = "Sam")

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
        assertEquals("the edited text is what the send receives", listOf<String?>("Please call me"), sent)
        assertEquals(0, dismissed)
    }

    @Test
    fun unnamedTarget_fallsBackToTheGenericTitle_andAClearedFieldSendsNoText() {
        render(targetName = null)

        compose.onNodeWithText(str(R.string.sos_confirm_title)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.sos_default_message)).performTextClearance()
        compose.onNodeWithText(str(R.string.sos_confirm_send)).performClick()
        compose.waitForIdle()
        assertEquals("blank must travel as null (v1 constant), never an empty string", listOf<String?>(null), sent)
    }

    @Test
    fun cancel_dismissesWithoutSending() {
        render(targetName = "Sam")

        compose.onNodeWithText(str(R.string.sos_confirm_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(emptyList<String?>(), sent)
    }
}
