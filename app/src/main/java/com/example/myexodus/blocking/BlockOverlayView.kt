package com.example.myexodus.blocking

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.myexodus.R

/** What the blocking overlay shows and which actions it offers for the current block. */
sealed interface OverlayContent {
    /** App block — the app block screen's card twin (ADR #32): names the app, states why, and
     *  offers the same single escape (the recovery action) as the URL card. Shown as the
     *  instant bridge before [BlockerActivity] lands and as the whole wall when the
     *  foreground service is down. [ruleId] is null only when the rule vanished between the
     *  decide and the show; the card then states the block without an action. */
    data class AppBlocked(val target: String, val ruleId: String?) : OverlayContent

    /** URL block — V1 State 2: names the site and offers the Interception Escape Hatch
     *  ("Open MyExodus Recovery", single tap to the recovery dashboard) plus leaving the
     *  blocked page for a different site. */
    data class UrlBlocked(
        val host: String,
        val ruleId: String,
        val browserPackage: String,
    ) : OverlayContent
}

/**
 * The block/pause surface drawn over a restricted window (V1 Req: Interception Escape Hatch
 * embedded in the active blocking overlay, ≤1 tap to recovery resources). Touch-consuming so
 * nothing reaches the app beneath; opaque so the blocked content is not readable through it.
 * `filterTouchesWhenObscured` per NFR-S10: taps are discarded while another window obscures
 * this one. Reused per display — [bind] re-renders it for every show.
 *
 * Painted in the block screen's own theme colors (user-directed 2026-09-13: the cover showed
 * dark, the BlockerActivity card light — the swap read as two pause screens; matching colors
 * and type make it one continuous surface). Keep in step with [BlockerActivity.BlockScreen].
 */
@SuppressLint("ViewConstructor") // built in code only — never inflated from XML
internal class BlockOverlayView(
    context: Context,
    private val onPresented: () -> Unit,
    private val onOpenRecovery: (ruleId: String) -> Unit,
    private val onDifferentSite: (browserPackage: String) -> Unit,
) : FrameLayout(context) {

    private val title = TextView(context).apply {
        setTextColor(0xFF1D1B19.toInt()) // TextLight
        textSize = 22f // titleLarge, as the block screen
        gravity = Gravity.CENTER
    }
    private val message = TextView(context).apply {
        setTextColor(0xFF6E675E.toInt()) // MutedLight (onSurfaceVariant)
        textSize = 14f // bodyMedium
        gravity = Gravity.CENTER
    }
    private val recoveryButton = overlayButton(R.string.overlay_open_recovery, primary = true)
    private val differentSiteButton = overlayButton(R.string.overlay_different_site, primary = false)
    private val hint = TextView(context).apply {
        setText(R.string.overlay_hint)
        setTextColor(0xFF6E675E.toInt()) // MutedLight
        textSize = 12f // bodySmall
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(0xFFFAF8F5.toInt()) // SandLight — the theme background
        filterTouchesWhenObscured = true
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // dp, not raw px: 64px rendered as a visibly different inset per density
                // (emulator-caught 2026-09-13 alongside the Dialog-decoration sizing defect).
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(message, marginParams(bottom = 32))
                addView(recoveryButton, marginParams(bottom = 8))
                addView(differentSiteButton)
                addView(hint, marginParams(top = 24))
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    fun bind(content: OverlayContent) {
        when (content) {
            is OverlayContent.AppBlocked -> {
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
                hint.visibility = VISIBLE
            }
            is OverlayContent.UrlBlocked -> {
                title.text = context.getString(R.string.blocked_target_title, content.host)
                message.setText(R.string.enhanced_mode_block_overlay)
                message.visibility = VISIBLE
                recoveryButton.visibility = VISIBLE
                differentSiteButton.visibility = VISIBLE
                hint.visibility = VISIBLE
                recoveryButton.setOnClickListener { onOpenRecovery(content.ruleId) }
                differentSiteButton.setOnClickListener { onDifferentSite(content.browserPackage) }
            }
        }
    }

    private fun overlayButton(textRes: Int, primary: Boolean): Button = Button(context).apply {
        setText(textRes)
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else 0xFF1D1B19.toInt())
        // Pill shape + ripple, as the Compose M3 buttons on the block screen; the cover must
        // not restyle at the hand-off. Paint order: ripple (masked to the pill) over the fill.
        val radius = 20 * resources.displayMetrics.density
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (primary) 0xFF0F766E.toInt() else 0xFFEFEAE2.toInt()) // TealLight / surfaceVariant
        }
        background = RippleDrawable(
            ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x22000000),
            fill,
            mask,
        )
        // Replacing the background drops the drawable's content insets (text ran edge-to-edge
        // over the pill) and the theme's button style keeps an elevation shadow the Compose
        // button does not have. M3 metrics instead: 24/8dp padding, 40dp min height, flat.
        val padH = (24 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        setPadding(padH, padV, padH, padV)
        minimumHeight = (40 * resources.displayMetrics.density).toInt()
        stateListAnimator = null
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    // Touch/click contract for accessibility: the surface itself has no action, but the
    // contract must hold for TalkBack and click handling to stay well-formed.
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
