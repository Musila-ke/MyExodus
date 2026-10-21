package com.example.myexodus.blocking

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.toArgb
import com.example.myexodus.R
import com.example.myexodus.ui.appLabel
import com.example.myexodus.ui.typeScaleFactor
import com.example.myexodus.ui.windowWidthFor
import com.example.myexodus.ui.theme.ErrorDark
import com.example.myexodus.ui.theme.ErrorLight
import com.example.myexodus.ui.theme.MutedDark
import com.example.myexodus.ui.theme.MutedLight
import com.example.myexodus.ui.theme.OnPrimaryContainerDark
import com.example.myexodus.ui.theme.OnPrimaryContainerLight
import com.example.myexodus.ui.theme.OnPrimaryDark
import com.example.myexodus.ui.theme.OnPrimaryLight
import com.example.myexodus.ui.theme.OutlineVariantDark
import com.example.myexodus.ui.theme.OutlineVariantLight
import com.example.myexodus.ui.theme.PrimaryContainerDark
import com.example.myexodus.ui.theme.PrimaryContainerLight
import com.example.myexodus.ui.theme.SandDark
import com.example.myexodus.ui.theme.SandLight
import com.example.myexodus.ui.theme.SurfaceDark
import com.example.myexodus.ui.theme.SurfaceLight
import com.example.myexodus.ui.theme.SurfaceVariantDark
import com.example.myexodus.ui.theme.SurfaceVariantLight
import com.example.myexodus.ui.theme.TealDark
import com.example.myexodus.ui.theme.TealLight
import com.example.myexodus.ui.theme.TextDark
import com.example.myexodus.ui.theme.TextLight

/** What the blocking overlay shows and which actions it offers for the current block. */
sealed interface OverlayContent {
    /** App block — the app block screen's card twin (ADR #32): names the app, states why, and
     *  offers the same single escape (the recovery action) as the URL card. Shown as the
     *  instant bridge before [BlockerActivity] lands and as the whole wall when the
     *  foreground service is down. [ruleId] is null only when the rule vanished between the
     *  decide and the show; the card then states the block without an action. [packageName]
     *  feeds the anchor disc's real icon; null when the target is not an installed package
     *  (the URL-race fallback), where the disc shows the block glyph.
     *
     *  Persistent since the owner ruling 2026-10-03: a foreign foreground no longer lifts it
     *  (that is what made the on-schedule block screen flash for a split second and drop the
     *  user to Home) — it stays until the user dismisses it via its Close action, or our own
     *  UI (the durable [BlockerActivity], the recovery flow) takes over. Mirrors the daily
     *  wall's 2026-09-27 ruling. */
    data class AppBlocked(
        val target: String,
        val ruleId: String?,
        val packageName: String? = null,
    ) : OverlayContent

    /** URL block — V1 State 2: names the site and offers the Interception Escape Hatch
     *  ("Open MyExodus Recovery", single tap to the recovery dashboard) plus leaving the
     *  blocked page for a different site. */
    data class UrlBlocked(
        val host: String,
        val ruleId: String,
        val browserPackage: String,
    ) : OverlayContent

    /** Daily-limit block (enforcement plan v2 §8 Step 10 `BlockOverlayContent(packageName)`):
     *  the app used up its Req 4 daily budget. Full-screen and touch-consuming over its whole
     *  bar-free area; the host evicts the app to the background the moment the window attaches
     *  (§8 Step 4 — covering alone left the app resumed, playing audio, with Digital Wellbeing
     *  still counting it, device-caught 2026-09-27). The wall then stays until the user taps
     *  its Close action (user ruling 2026-09-27) — never a timer. */
    data class DailyLimitBlocked(val packageName: String) : OverlayContent

    /** Usage warning (plan §8 Step 10 `WarningOverlayContent(level, remainingMs)`) — a
     *  top-banner countdown of the remaining budget. Non-focusable, touches outside the
     *  banner pass through; the host auto-dismisses it after 5 seconds or on tap. [packageName]
     *  is not painted (the copy is generic) — the deciding service uses it to tell the tracked
     *  app's own window churn (banner keeps its 5s) from an app switch (banner yields). */
    data class Warning(
        val packageName: String,
        val level: Int,
        val remainingMs: Long,
    ) : OverlayContent
}

/**
 * The block/pause surface drawn over a restricted window (V1 Req: Interception Escape Hatch
 * embedded in the active blocking overlay, ≤1 tap to recovery resources). Touch-consuming so
 * nothing reaches the app beneath; opaque so the blocked content is not readable through it.
 * `filterTouchesWhenObscured` per NFR-S10: taps are discarded while another window obscures
 * this one. Reused per display — [bind] re-renders it for every show.
 *
 * Painted from the Desert Dawn palette constants, resolved per system dark mode at every
 * [bind] (audit finding #16: hardcoded light hexes made the cover stay light while the
 * dark-mode [BlockerActivity] card went dark — the same two-pause-screens swap the user
 * flagged on 2026-09-13, mirrored). This view is outside Composition, so it reads the shared
 * `ui.theme` constants directly rather than `MaterialTheme`; keep it that way — one palette.
 *
 * 2026-09-27 design pass (UI sweep): the block walls anchor on the target's real icon in a
 * soft-teal disc (the app block screen's disc in Compose — one language), and the warning
 * strip is an inset surface card with a timer glyph, not a bare centered line. The warning
 * window is still the capped top strip; only its content inset changed.
 */
@SuppressLint("ViewConstructor") // built in code only — never inflated from XML
internal class BlockOverlayView(
    context: Context,
    private val onPresented: () -> Unit,
    private val onOpenRecovery: (ruleId: String) -> Unit,
    private val onDifferentSite: (browserPackage: String) -> Unit,
    /** The persistent walls' in-place Close (daily wall + app-block card, owner rulings
     *  2026-09-27 / 2026-10-03); null on a host that cannot evict (Path B), which keeps its
     *  follow-the-foreground dismissal and renders no in-place dismiss. */
    private val onWallDismiss: (() -> Unit)? = null,
    private val onWarningDismiss: () -> Unit = {},
) : FrameLayout(context) {

    private var bound: OverlayContent? = null

    /** One-shot callback armed by [bind], fired on the first pre-draw after the window is
     *  (or already was) attached — the host's "the card is actually on screen" signal (§8
     *  Step 4: eviction runs only on a confirmed attach, never on the assumption). */
    private var pendingAttached: (() -> Unit)? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** The Compose world's font (SansFamily = Work Sans, Type.kt). Absent this, every TextView
     *  here rendered Roboto from the window theme while the durable BlockerActivity rendered
     *  Work Sans — the same card swapping typeface mid-hand-off (task #4 audit). Loaded once
     *  per view; a load failure degrades to the themed font, never to invisible text. */
    private val brandTypeface: Typeface? by lazy {
        runCatching { ResourcesCompat.getFont(context, R.font.work_sans) }.getOrNull()
    }

    /** One Compose text role on a TextView: size, family, weight, tracking, and no font
     *  padding — Compose has none, so leaving the View default on would pad every text block
     *  taller than its Compose twin and shift the whole wall down by a few px per line.
     *  Weight rides the variable-font axis on API 26+; below that the default instance stands
     *  (a weight step, not a different letterform). */
    private fun applyText(
        view: TextView,
        sizeSp: Float,
        factor: Float,
        weight: Int,
        letterSpacingEm: Float,
    ) {
        view.textSize = sizeSp * factor
        view.includeFontPadding = false
        view.letterSpacing = letterSpacingEm
        val typeface = brandTypeface ?: return
        view.typeface = typeface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.fontVariationSettings = "'wght' $weight"
        }
    }

    /** The adaptive type ramp the Compose world uses (Adaptive.kt) — the overlay covers the
     *  full display, so the display's width class is the window class. Read per bind: a reused
     *  window can land on another display or be resized (split screen). Width comes from the
     *  live display metrics, never `configuration.screenWidthDp`: the display context's
     *  Configuration lags a display-size change (device-caught 2026-09-27 on the X6532 — the
     *  config read 360dp while the display was 1600px/320dpi, so the MEDIUM ramp never
     *  applied; the metrics were live in the same read). The letter-spacing values are the
     *  Compose roles' sp trackings converted to em at their own size (bodyMedium 0.25/14 etc.). */
    private fun applyTypeScale() {
        val metrics = resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).toInt()
        val factor = windowWidthFor(widthDp).typeScaleFactor
        if (com.example.myexodus.BuildConfig.DEBUG) {
            Log.i("MyExodusOverlay", "overlay typeScale widthDp=$widthDp factor=$factor")
        }
        applyText(title, 22f, factor, weight = 600, letterSpacingEm = 0f) // titleLarge
        applyText(message, 14f, factor, weight = 400, letterSpacingEm = 0.25f / 14f) // bodyMedium
        applyText(hint, 12f, factor, weight = 400, letterSpacingEm = 0.4f / 12f) // bodySmall
        applyText(warningText, 16f, factor, weight = 400, letterSpacingEm = 0.5f / 16f) // bodyLarge
        listOf(recoveryButton, differentSiteButton, wallDismissButton).forEach { button ->
            applyText(button, 14f, factor, weight = 500, letterSpacingEm = 0.1f / 14f) // labelLarge
        }
        val discSize = dp((88 * factor).toInt())
        disc.layoutParams = LinearLayout.LayoutParams(discSize, discSize).apply { bottomMargin = dp(24) }
        val iconSize = dp((48 * factor).toInt())
        discIcon.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
    }

    /** Debug seam: the same Close listener a finger fires. False when no dismissible wall is
     *  bound — this host's own overlay is invisible to `visibleWindows()`, so the device suite
     *  cannot reach it through the accessibility tree (2026-10-20: dismiss "passed" no-op). */
    internal fun debugClickDismiss(): Boolean =
        wallDismissButton.visibility == android.view.View.VISIBLE && wallDismissButton.performClick()

    /** The wall's visual anchor: the blocked target's real icon on a soft-teal disc. */
    private val discIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
    }
    private val disc = FrameLayout(context).apply {
        addView(discIcon)
        layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(24) }
    }

    private val title = TextView(context).apply {
        textSize = 22f // titleLarge, as the block screen
        gravity = Gravity.CENTER
    }
    private val message = TextView(context).apply {
        textSize = 14f // bodyMedium
        gravity = Gravity.CENTER
    }
    private val recoveryButton = overlayButton(R.string.overlay_open_recovery)
    private val differentSiteButton = overlayButton(R.string.overlay_different_site)
    private val wallDismissButton = overlayButton(R.string.overlay_wall_dismiss)
    private val hint = TextView(context).apply {
        setText(R.string.overlay_hint)
        textSize = 12f // bodySmall
        gravity = Gravity.CENTER
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        // dp, not raw px: 64px rendered as a visibly different inset per density
        // (emulator-caught 2026-09-13 alongside the Dialog-decoration sizing defect).
        // 32dp = the block screen Column's padding (task #4): one inset, so text wraps at the
        // same width on both surfaces.
        val pad = dp(32)
        setPadding(pad, pad, pad, pad)
        addView(disc)
        addView(title)
        addView(message, marginParams(bottom = 32))
        addView(recoveryButton, marginParams(bottom = 8))
        addView(differentSiteButton)
        addView(wallDismissButton, marginParams(bottom = 24))
        addView(hint, marginParams(top = 24))
    }

    /** The warning strip's card: a surface inset from the strip edges, one line of copy with
     *  a leading timer glyph. Its own text view (16sp bodyLarge) — the wall's 22sp title is a
     *  different role, and sharing one view across the two meant re-parenting on every bind. */
    private val warningText = TextView(context).apply {
        textSize = 16f
        maxLines = 2
        gravity = Gravity.CENTER
        compoundDrawablePadding = dp(12)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val warningCard = LinearLayout(context).apply {
        addView(warningText)
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
    }

    init {
        filterTouchesWhenObscured = true
        addView(
            column,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(warningCard)
    }

    fun bind(content: OverlayContent, onAttached: (() -> Unit)? = null) {
        bound = content
        pendingAttached = onAttached
        applyTypeScale()
        applyPalette()
        val warning = content is OverlayContent.Warning
        column.visibility = if (warning) GONE else VISIBLE
        warningCard.visibility = if (warning) VISIBLE else GONE
        // Per-part visibility, not just the containers': the a11y tree and the view tests
        // read child visibility directly, and a hidden container must not leak stale copy.
        // Every toggle is two-way: a one-way `GONE` on the warning path left the reused
        // view's title hidden through a later wall bind — the daily-limit wall rendered
        // without its title after any banner (device-caught 2026-09-27 on the X6532).
        warningText.visibility = if (warning) VISIBLE else GONE
        disc.visibility = if (warning) GONE else VISIBLE
        title.visibility = if (warning) GONE else VISIBLE
        if (warning) {
            message.visibility = GONE
            recoveryButton.visibility = GONE
            differentSiteButton.visibility = GONE
            wallDismissButton.visibility = GONE
            hint.visibility = GONE
        }
        when (content) {
            is OverlayContent.AppBlocked -> {
                showTarget(content.packageName, fallbackRes = R.drawable.ic_block)
                title.text = context.getString(R.string.blocked_target_title, content.target)
                message.setText(R.string.enhanced_mode_block_overlay)
                message.visibility = VISIBLE
                val ruleId = content.ruleId
                if (ruleId != null) {
                    recoveryButton.visibility = VISIBLE
                    recoveryButton.setOnClickListener { onOpenRecovery(ruleId) }
                } else {
                    recoveryButton.visibility = GONE
                }
                differentSiteButton.visibility = GONE
                // Persistent like the daily wall (owner ruling 2026-10-03): the evicting host
                // arms the same in-place Close, so the user dismisses the card — the launcher
                // coming forward after the eviction no longer lifts it mid-flash.
                val dismiss = onWallDismiss
                wallDismissButton.visibility = if (dismiss != null) VISIBLE else GONE
                if (dismiss != null) wallDismissButton.setOnClickListener { dismiss() }
                hint.visibility = VISIBLE
            }
            is OverlayContent.UrlBlocked -> {
                showTarget(packageName = null, fallbackRes = R.drawable.ic_globe)
                title.text = context.getString(R.string.blocked_target_title, content.host)
                message.setText(R.string.enhanced_mode_block_overlay)
                message.visibility = VISIBLE
                recoveryButton.visibility = VISIBLE
                differentSiteButton.visibility = VISIBLE
                wallDismissButton.visibility = GONE
                hint.visibility = VISIBLE
                recoveryButton.setOnClickListener { onOpenRecovery(content.ruleId) }
                differentSiteButton.setOnClickListener { onDifferentSite(content.browserPackage) }
            }
            is OverlayContent.DailyLimitBlocked -> {
                showTarget(content.packageName, fallbackRes = R.drawable.ic_block)
                title.text = context.getString(
                    R.string.limit_blocked_title,
                    appLabel(context, content.packageName),
                )
                message.setText(R.string.limit_blocked_message)
                message.visibility = VISIBLE
                recoveryButton.visibility = GONE
                differentSiteButton.visibility = GONE
                val dismiss = onWallDismiss
                wallDismissButton.visibility = if (dismiss != null) VISIBLE else GONE
                if (dismiss != null) wallDismissButton.setOnClickListener { dismiss() }
                hint.visibility = VISIBLE
            }
            is OverlayContent.Warning -> {
                // Ceiling, not floor: 4m05s left reads "about 5 min", never "4 min" (§8 Step 10).
                val minutes = ((content.remainingMs + 59_999) / 60_000).toInt()
                warningText.text = context.getString(
                    if (content.level >= 2) R.string.limit_warning_title_final else R.string.limit_warning_title,
                    minutes,
                )
                // No entrance animation: the alpha-0 + post{animate} reveal shipped invisible
                // on the X6532 (window HAS_DRAWN + delivery-confirmed, zero pixels painted —
                // device-caught 2026-09-27). The banner is a 5s transient; a hard appearance
                // is the honest default.
            }
        }
        // Block walls = the v1 block screen's rhythm with their in-place Close (user-directed
        // 2026-09-15 rhythm; the escape became a wall control on 2026-09-27 — the app is
        // already evicted behind it, so a tap must not be the only way out). The app-block
        // card joined the wall on 2026-10-03 — it too stays until the user dismisses it.
        // This is the SHARED rhythm (task #4): BlockerActivity.BlockScreen renders the same
        // composition — disc 24 title 8 message 32 recovery 8 Close 24 hint — so the a11y
        // cover cannot restyle when the durable screen takes over.
        val blocked = content is OverlayContent.DailyLimitBlocked || content is OverlayContent.AppBlocked
        message.layoutParams =
            if (blocked) marginParams(top = 8, bottom = 32) else marginParams(bottom = 32)
        wallDismissButton.layoutParams =
            if (blocked) marginParams(bottom = 24) else marginParams(bottom = 8)
        hint.layoutParams = if (blocked) marginParams() else marginParams(top = 24)
        hint.setText(if (blocked) R.string.block_screen_hint else R.string.overlay_hint)
        // Rebind of an already-attached window (warning → block upgrade in place, §8 Step 10):
        // no attach event will fire again, so arm the confirm callback for the next frame.
        if (onAttached != null && isAttachedToWindow) scheduleAttachCallback()
    }

    /** Disc + icon for a block wall: the target package's real launcher icon (the app block
     *  screen shows the same), else [fallbackRes] as a glyph tinted on-container. Purely
     *  decorative — the title names the target as text. */
    private fun showTarget(packageName: String?, fallbackRes: Int) {
        val icon = packageName?.let {
            runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull()
        }
        if (icon != null) {
            discIcon.setImageDrawable(icon)
            discIcon.clearColorFilter()
        } else {
            discIcon.setImageResource(fallbackRes)
            discIcon.setColorFilter(onPrimaryContainer())
        }
    }

    /** Scheme switch for this view: the block screen follows `isSystemInDarkTheme`, and this
     *  cover must follow the same signal to stay one surface. Read at [bind], not construction,
     *  so a reused window repaints correctly after a day/night switch. */
    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun onPrimaryContainer(): Int =
        (if (night) OnPrimaryContainerDark else OnPrimaryContainerLight).toArgb()

    private fun applyPalette() {
        // A warning is an inset card on an otherwise empty strip — the app beneath stays
        // visible around it. Block walls stay opaque: the covered content must not be readable.
        val warning = bound is OverlayContent.Warning
        setBackgroundColor(
            if (warning) Color.TRANSPARENT else (if (night) SandDark else SandLight).toArgb(),
        )
        disc.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((if (night) PrimaryContainerDark else PrimaryContainerLight).toArgb())
        }
        warningText.setTextColor((if (night) TextDark else TextLight).toArgb())
        warningText.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor((if (night) SurfaceDark else SurfaceLight).toArgb())
            setStroke(dp(1), (if (night) OutlineVariantDark else OutlineVariantLight).toArgb())
        }
        warningText.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_timer, 0, 0, 0)
        val level = (bound as? OverlayContent.Warning)?.level ?: 1
        warningText.compoundDrawablesRelative.getOrNull(0)?.setTintList(
            ColorStateList.valueOf(
                // Level 2 is the last call: the glyph wears the error tone (the words already
                // say it — color is never the only signal).
                (if (level >= 2) (if (night) ErrorDark else ErrorLight) else (if (night) TealDark else TealLight)).toArgb(),
            ),
        )
        title.setTextColor((if (night) TextDark else TextLight).toArgb())
        message.setTextColor((if (night) MutedDark else MutedLight).toArgb())
        hint.setTextColor((if (night) MutedDark else MutedLight).toArgb())
        paintButton(recoveryButton, primary = true)
        paintButton(differentSiteButton, primary = false)
        paintButton(wallDismissButton, primary = true)
    }

    private fun overlayButton(textRes: Int): Button = Button(context).apply {
        setText(textRes)
        isAllCaps = false
        // Replacing the background drops the drawable's content insets (text ran edge-to-edge
        // over the pill) and the theme's button style keeps an elevation shadow the Compose
        // button does not have. M3 metrics instead: 24/8dp padding, 40dp min height, flat.
        val padH = dp(24)
        val padV = dp(8)
        setPadding(padH, padV, padH, padV)
        minimumHeight = dp(40)
        stateListAnimator = null
    }

    /** Pill shape + ripple, as the Compose M3 buttons on the block screen; the cover must not
     *  restyle at the hand-off. Paint order: ripple (masked to the pill) over the fill. */
    private fun paintButton(button: Button, primary: Boolean) {
        val onPrimary = OnPrimaryLight.toArgb()
        val onPrimaryDark = OnPrimaryDark.toArgb()
        button.setTextColor(
            if (primary) (if (night) onPrimaryDark else onPrimary) else (if (night) TextDark else TextLight).toArgb(),
        )
        val radius = 20 * resources.displayMetrics.density
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(
                if (primary) {
                    (if (night) TealDark else TealLight).toArgb()
                } else {
                    (if (night) SurfaceVariantDark else SurfaceVariantLight).toArgb()
                },
            )
        }
        button.background = RippleDrawable(
            ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x22000000),
            fill,
            mask,
        )
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply {
                this.topMargin = top
                this.bottomMargin = bottom
            }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // One-shot: left attached, the listener fired every frame for the window's whole
        // lifetime and accumulated one per hide/show cycle (reused per display).
        val observer = viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                observer.removeOnPreDrawListener(this)
                onPresented()
                return true
            }
        })
        if (pendingAttached != null) scheduleAttachCallback()
    }

    private fun scheduleAttachCallback() {
        val observer = viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                observer.removeOnPreDrawListener(this)
                val callback = pendingAttached
                pendingAttached = null
                callback?.invoke()
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    // Touch/click contract for accessibility: block cards have no self action, but the
    // contract must hold for TalkBack and click handling to stay well-formed. A tap on a
    // warning banner is the "dismiss" gesture (§8 Step 4).
    override fun performClick(): Boolean {
        super.performClick()
        if (bound is OverlayContent.Warning) onWarningDismiss()
        return true
    }
}
