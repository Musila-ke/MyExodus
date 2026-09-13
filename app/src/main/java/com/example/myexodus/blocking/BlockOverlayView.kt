package com.example.myexodus.blocking

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
 */
@SuppressLint("ViewConstructor") // built in code only — never inflated from XML
internal class BlockOverlayView(
    context: Context,
    private val onPresented: () -> Unit,
    private val onOpenRecovery: (ruleId: String) -> Unit,
    private val onDifferentSite: (browserPackage: String) -> Unit,
) : FrameLayout(context) {

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
    }
    private val message = TextView(context).apply {
        setTextColor(0xFFB9BDC4.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
    }
    private val recoveryButton = overlayButton(R.string.overlay_open_recovery)
    private val differentSiteButton = overlayButton(R.string.overlay_different_site)
    private val hint = TextView(context).apply {
        setText(R.string.overlay_hint)
        setTextColor(0xFF8A9099.toInt())
        textSize = 13f
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(0xFF15171A.toInt())
        filterTouchesWhenObscured = true
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
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

    private fun overlayButton(textRes: Int): Button = Button(context).apply {
        setText(textRes)
        isAllCaps = false
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
