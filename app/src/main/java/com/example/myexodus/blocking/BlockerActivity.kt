package com.example.myexodus.blocking

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
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
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberTypeScaleFactor
import com.example.myexodus.settings.ThemeSettings
import com.example.myexodus.ui.theme.MyExodusTheme
import com.example.myexodus.ui.theme.rememberDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
 * Since the task #4 consistency audit it also carries the overlay card's Close (owner rulings
 * 2026-09-27 / 2026-10-03: every block wall stays until the user dismisses it): the a11y
 * cover and this screen are now the identical composition, so the hand-off cannot restyle.
 * Close routes through Home ([leaveToHome]): a bare finish() resumed the target still behind
 * the wall, the FGS tick re-blocked it as a brand-new attempt (a duplicate partner event) and
 * the wall collapsed mid-transition (owner-observed 2026-11-02).
 *
 * On stop (Home, keyguard, another window) the screen dismisses itself; it is not kept
 * alive in the background.
 */
@AndroidEntryPoint
class BlockerActivity : ComponentActivity() {

    @Inject lateinit var engine: BlockDecisionEngine
    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var recoveryStore: RecoveryStateStore
    @Inject lateinit var themeSettings: ThemeSettings

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
            MyExodusTheme(darkTheme = rememberDarkTheme(themeSettings.mode)) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BlockScreen(
                        appLabel = rememberAppLabel(targetPackage),
                        packageName = targetPackage,
                        // IO: the session write opens the store — a synchronous SQLCipher call
                        // that must never run on Main while a wall is up (ADR #63).
                        onOpenRecovery = { lifecycleScope.launch(Dispatchers.IO) { openRecovery() } },
                        onClose = { leaveToHome() },
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

    /** Close means "leave the wall", not "reveal the blocked app". Home must be resumed
     *  BEFORE this activity goes away: finishing in place hands RESUMED back to the target,
     *  which the FGS tick reads as a fresh blocked launch (new partner event) and which
     *  plays the wall's close animation. onStop finishes us once the launcher is up. */
    private fun leaveToHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
                .putExtra(MainActivity.EXTRA_RECOVERY_ENTRY, RecoverySession.ENTRY_BLOCK_SCREEN)
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
    packageName: String,
    onOpenRecovery: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same adaptive ramp the View overlay reads (Adaptive.kt) — disc and glyph step up
    // with the window class alongside the themed type.
    val scale = rememberTypeScaleFactor()
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The wall's anchor (UI sweep 2026-09-27): the blocked app's real icon on a soft-teal
        // disc — the same composition the View overlay paints for its walls, one language.
        Box(
            modifier = Modifier
                .size((88 * scale).dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppMark(
                packageName = packageName,
                fallback = Icons.Filled.Block,
                size = (48 * scale).dp,
                fallbackTint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
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
        // The shared wall rhythm through the Close (task #4): the overlay card renders
        // [recovery] 8 [Close] 24 [hint] — this screen must match exactly.
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClose) {
            Text(stringResource(R.string.overlay_wall_dismiss))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.block_screen_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
