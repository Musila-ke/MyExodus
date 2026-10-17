package com.example.myexodus.ui.recovery

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import com.example.myexodus.recovery.RecoveryResourceCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finding #54: Pause used to flip `running` alone, which re-entered the duration-chooser
 * branch — the pacer left composition, its elapsed clock was lost, and the user was asked to
 * start over. Pausing must keep the ring and offer Resume; only the pre-start state shows the
 * chooser.
 */
@RunWith(AndroidJUnit4::class)
class PacerPauseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(resId: Int, vararg args: Any): String = compose.activity.getString(resId, *args)

    @Test
    fun pause_keepsTheSession_andResumeReturnsToTheRing() {
        compose.setContent {
            TechniqueRunner(
                technique = checkNotNull(RecoveryResourceCatalog.byId(RecoveryResourceCatalog.URGE_SURFING)),
                planItems = emptyList(),
                hapticEnabled = false,
                onHapticChange = {},
                onCompleted = {},
                onStop = {},
            )
        }

        compose.onNodeWithText(str(R.string.recovery_pacer_start)).performClick()
        compose.onNodeWithText(str(R.string.recovery_pacer_pause)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.recovery_pacer_pause)).performClick()

        compose.onNodeWithText(str(R.string.recovery_pacer_choose_duration)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.recovery_pacer_resume)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.recovery_pacer_resume)).performClick()
        compose.onNodeWithText(str(R.string.recovery_pacer_pause)).assertIsDisplayed()
    }
}
