package com.example.myexodus.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.account.AuthFailure
import com.example.myexodus.account.AuthState
import com.example.myexodus.account.GoogleCredentialOutcome
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.BrandLockup
import com.example.myexodus.ui.settings.AccountAuthForm
import com.example.myexodus.ui.settings.AccountViewModel
import com.example.myexodus.ui.settings.failureRes
import kotlinx.coroutines.launch

private const val SLIDE_COUNT = 3
private const val PAGE_ACCOUNT = SLIDE_COUNT
private const val PAGE_PERMISSIONS = SLIDE_COUNT + 1

private data class OnboardingSlide(
    val kickerRes: Int,
    val titleRes: Int,
    val bodyRes: Int,
    val photoRes: Int,
)

private val Slides = listOf(
    OnboardingSlide(
        R.string.onboarding_slide_block_kicker,
        R.string.onboarding_slide_block_title,
        R.string.onboarding_slide_block_body,
        R.drawable.onboarding_block,
    ),
    OnboardingSlide(
        R.string.onboarding_slide_widget_kicker,
        R.string.onboarding_slide_widget_title,
        R.string.onboarding_slide_widget_body,
        R.drawable.onboarding_rhythm,
    ),
    OnboardingSlide(
        R.string.onboarding_slide_partner_kicker,
        R.string.onboarding_slide_partner_title,
        R.string.onboarding_slide_partner_body,
        R.drawable.onboarding_partner,
    ),
)

/**
 * First-run journey (owner request 2026-11-06): three capability slides, then the optional
 * account choice, then the permission wizard unchanged in behavior. The account form is the
 * Settings pane's own [AccountAuthForm], so validation and copy cannot drift between the two
 * entry points; only the surrounding choice ("continue without an account") is new.
 *
 * Visual direction (owner correction 2026-11-06): photographic hero cards and Fraunces
 * headlines instead of tinted icon plates — the app should read as designed, not templated.
 *
 * Back walks the journey backwards. Inside the permission wizard its own handler steps through
 * the wizard first (ADR #91); at the wizard's first step this outer handler returns to the
 * account choice instead of dead-ending.
 */
@Composable
fun OnboardingScreen(
    monitor: EnhancedModeHealthMonitor,
    accountViewModel: AccountViewModel,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = page > 0) { page-- }

    if (page == PAGE_PERMISSIONS) {
        SetupScreen(monitor = monitor, onFinish = onFinish, modifier = modifier)
        return
    }

    val accountState by accountViewModel.state.collectAsStateWithLifecycle()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            OnboardingTopBar(
                showBack = page > 0,
                onBack = { page-- },
                showSkip = page < SLIDE_COUNT,
                onSkip = { page = PAGE_ACCOUNT },
            )
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(320)) { direction * it / 5 } +
                        fadeIn(tween(240, delayMillis = 30)))
                        .togetherWith(
                            slideOutHorizontally(tween(220)) { -direction * it / 5 } +
                                fadeOut(tween(160)),
                        )
                },
                modifier = Modifier.weight(1f),
                label = "onboardingPage",
            ) { current ->
                if (current < SLIDE_COUNT) {
                    SlidePage(
                        slide = Slides[current],
                        index = current,
                        onNext = { page++ },
                    )
                } else {
                    AccountPage(
                        state = accountState,
                        viewModel = accountViewModel,
                        onContinue = { page = PAGE_PERMISSIONS },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(
    showBack: Boolean,
    onBack: () -> Unit,
    showSkip: Boolean,
    onSkip: () -> Unit,
) {
    val skip: @Composable () -> Unit = {
        if (showSkip) {
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.setup_skip)) }
        }
    }
    if (showBack) {
        // ADR #90: the way out of a step is the shared BackRow, never a hand-rolled one.
        BackRow(
            label = stringResource(R.string.app_name),
            onBack = onBack,
            trailing = skip,
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandLockup(height = 40.dp)
            Spacer(Modifier.weight(1f))
            skip()
        }
    }
}

@Composable
private fun SlidePage(
    slide: OnboardingSlide,
    index: Int,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SlidePhoto(slide)
            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(slide.titleRes),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(slide.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlideBars(index)
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext, modifier = Modifier.height(52.dp)) {
                Text(stringResource(R.string.setup_next))
            }
        }
    }
}

/** The photographic half of the slide: a full-width card, softly rounded, with the capability
 *  kicker set on the photo itself. The scrim only exists to keep that label legible. */
@Composable
private fun SlidePhoto(slide: OnboardingSlide) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(282.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), shape),
    ) {
        Image(
            painter = painterResource(slide.photoRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(22.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(slide.kickerRes).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.2.sp),
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

@Composable
private fun SlideBars(index: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(SLIDE_COUNT) { dot ->
            val width by animateDpAsState(
                targetValue = if (dot == index) 30.dp else 10.dp,
                label = "slideBar",
            )
            Box(
                Modifier
                    .width(width)
                    .height(3.dp)
                    .background(
                        if (dot == index) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun AccountPage(
    state: AuthState,
    viewModel: AccountViewModel,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var googleFailure by remember { mutableStateOf<AuthFailure?>(null) }

    // A done account action (create, sign-in, Google) swaps the form for a confirmation the
    // user leaves with a tap. No auto-advance: returning here from the permissions via Back
    // must not bounce straight forward again.
    (state as? AuthState.PersistentActive)?.let { signedIn ->
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EditorialKicker(stringResource(R.string.onboarding_account_kicker))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.onboarding_account_signed_in_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.onb_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(32.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.onboarding_account_signed_in_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                signedIn.email ?: stringResource(R.string.account_google_account),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.onboarding_account_signed_in_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text(stringResource(R.string.setup_next)) }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // Edge-to-edge: without the IME inset the keyboard covers the form's last
                // field (confirm password) and no amount of scrolling reveals it.
                .imePadding(),
        ) {
            EditorialKicker(stringResource(R.string.onboarding_account_kicker))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.account_guest_intro_title),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.account_guest_intro_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            AccountAuthForm(
                hasGuestUid = state is AuthState.GuestActive,
                // Onboarding has no completed transition to resume; a fresh install links in place.
                prepared = false,
                googleAvailable = viewModel.googleAvailable,
                viewModel = viewModel,
                onSignIn = { email, password -> viewModel.signInWithEmail(email, password) },
                onGoogle = {
                    scope.launch {
                        when (val result = viewModel.requestGoogleCredential(context)) {
                            is GoogleCredentialOutcome.Success ->
                                viewModel.continueWithGoogle(result.idToken, switchAccount = false)
                            GoogleCredentialOutcome.Cancelled -> Unit
                            is GoogleCredentialOutcome.Unavailable -> googleFailure = result.failure
                        }
                    }
                },
            )
            if (state is AuthState.Authenticating) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp)) }
            }
            val failure = (state as? AuthState.AuthError)?.failure ?: googleFailure
            if (failure != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(failureRes(failure)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                googleFailure = null
                                viewModel.dismissTransient()
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(stringResource(R.string.account_dismiss)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text(stringResource(R.string.onboarding_account_continue)) }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_account_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        )
    }
}

/** Small gold rule + uppercase label — the one accent that ties slides, account and wizard. */
@Composable
internal fun EditorialKicker(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(22.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.2.sp),
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
