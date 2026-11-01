package com.example.myexodus.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-AVD rig regression (2026-11-01, SOS leg 3): a reach-out card prepended into Home's keyed
 * LazyColumn while the user is on Home stays above the viewport — the list keeps the row the
 * user was looking at anchored — so the card exists in the ViewModel yet is never shown until
 * the user scrolls. [RevealPrependedCard] must scroll it back into view.
 */
@RunWith(AndroidJUnit4::class)
class RevealPrependedCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cardPrependedWhileTheListIsOpen_isScrolledIntoView() {
        var cardPresent by mutableStateOf(false)
        compose.setContent {
            val listState = rememberLazyListState()
            RevealPrependedCard(cardPresent = cardPresent, listState = listState)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (cardPresent) {
                    item(key = "nudge") {
                        Text("partner reach-out", modifier = Modifier.height(160.dp))
                    }
                }
                // Taller than the test viewport, like Home's real content.
                item(key = "status") {
                    Text("status header", modifier = Modifier.height(2000.dp))
                }
            }
        }

        compose.onNodeWithText("status header").assertIsDisplayed()
        compose.onNodeWithText("partner reach-out").assertDoesNotExist()

        compose.runOnIdle { cardPresent = true }
        compose.waitForIdle()

        compose.onNodeWithText("partner reach-out").assertIsDisplayed()
    }
}
