package com.example.myexodus.blocking

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.theme.MyExodusTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The app-block screen (blocking-core plan §8.1 Decision 3): a real Activity, launched with
 * FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP, with Back intercepted (legacy + predictive-back
 * callback) so the gesture cannot dismiss into the target app. Home always works — the
 * escape hatch invariant (§10) — and the FGS relaunches the screen when the target returns.
 *
 * The screen is the URL pause card's twin (ADR #31): the blocked app names the block, states
 * why, and offers the same single escape — "Open MyExodus Recovery" — which starts the
 * friction session and lands on the Recovery tab, where the countdown and the cooldown-gated
 * override live for both block types (Req 13, NFR "max 1 tap to recovery resources"). The
 * friction flow is not re-implemented here (user-directed 2026-09-13: one block language
 * across app and URL blocks).
 *
 * On stop (Home, keyguard, another window) the screen dismisses itself; it is not kept
 * alive in the background.
 */
@AndroidEntryPoint
class BlockerActivity : ComponentActivity() {

    @Inject lateinit var engine: BlockDecisionEngine
    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var recoveryStore: RecoveryStateStore

    private val targetPackage: String by lazy { checkNotNull(intent.getStringExtra(EXTRA_PACKAGE)) }
    private val targetRuleId: String by lazy { checkNotNull(intent.getStringExtra(EXTRA_RULE_ID)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tapjacking: the blocked app may draw its own window over this screen to capture
        // taps on the escape action. Drop every touch arriving while the window is obscured
        // by another window (View API, no dependency; applies to the whole window, Compose
        // buttons included).
        window.decorView.filterTouchesWhenObscured = true
        // Back cannot dismiss into the target (plan §8.1): intercepts both the legacy
        // onBackPressed path and the predictive-back callback.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op */ }
        })
        setContent {
            MyExodusTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BlockScreen(
                        appLabel = rememberAppLabel(targetPackage),
                        onOpenRecovery = { lifecycleScope.launch { openRecovery() } },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
        // Startup recompute (§8.1, no stale-overlay detection): re-decide from current state.
        if (!stillBlocked()) finish()
    }

    override fun onStop() {
        super.onStop()
        // Never linger in the background: Home/keyguard stop the screen, and the FGS
        // relaunches it when the target returns to the foreground.
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        EnforcementForegroundService.activeBlockTarget = null
        super.onDestroy()
    }

    private fun stillBlocked(): Boolean {
        val rule = publisher.snapshot.appRules[targetPackage] ?: return false
        return engine.decideApp(rule.packageName, System.currentTimeMillis()) is BlockDecision.Block
    }

    /** Interception Escape Hatch, same mechanics as the URL card's button (ADR #29): start
     *  the dual-clock friction session, then land on the Recovery tab — where the countdown
     *  and the cooldown-bounded override surface for both block types. A failed session
     *  write must not strand the user on the wall: the navigation still happens. */
    private suspend fun openRecovery() {
        runCatching { recoveryStore.startRecovery(targetRuleId) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_RECOVERY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_blocked_package"
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}

@Composable
private fun BlockScreen(
    appLabel: String,
    onOpenRecovery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.blocked_target_title, appLabel),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.enhanced_mode_block_overlay),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onOpenRecovery) {
            Text(stringResource(R.string.overlay_open_recovery))
        }
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.block_screen_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
