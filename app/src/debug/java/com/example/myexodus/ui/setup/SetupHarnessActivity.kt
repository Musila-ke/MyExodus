package com.example.myexodus.ui.setup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.ui.theme.MyExodusTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug-only harness: the first-run wizard is reachable exactly once per install (its gate
 * records "setup seen"), which makes it unverifiable on a device whose app data must survive —
 * the physical test phone, which is never uninstalled and never cleared. This renders the
 * production [SetupScreen] with the real monitor and a finish that closes the harness, so
 * layout, status lines, skipping, and back behavior are observed exactly as a first run shows
 * them. Debug variant only; ADR #91.
 */
@AndroidEntryPoint
class SetupHarnessActivity : ComponentActivity() {

    @Inject lateinit var monitor: EnhancedModeHealthMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyExodusTheme {
                SetupScreen(monitor = monitor, onFinish = { finish() })
            }
        }
    }
}
