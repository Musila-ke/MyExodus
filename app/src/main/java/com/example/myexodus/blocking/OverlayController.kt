package com.example.myexodus.blocking

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.app.AppOpsManager
import android.app.Dialog
import android.os.Process
import android.view.Display
import androidx.core.graphics.drawable.toDrawable
import com.example.myexodus.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-display blocking overlay (Enhanced Mode spec §8 Step 5, Architecture Decisions 2/10/11).
 * One reused window per display, `TYPE_ACCESSIBILITY_OVERLAY`, touch-consuming, never
 * `FLAG_NOT_TOUCHABLE`. `show`/`hide` both no-op unless the passed generation is still the
 * current one; a failed show is logged with its failure category and leaves the overlay in a
 * known not-shown state.
 *
 * Window mechanism (§8 Step 5 OEM-exception pattern): stock Android presents the overlay via
 * a lightweight [Dialog] subclass whose real [Window] carries the [LatencyProfiler]
 * FrameMetrics listener (the plan's AD 11.2 rationale — a bare added View has no Window).
 * Transsion XOS rejects token-null window adds for TYPE_ACCESSIBILITY_OVERLAY with
 * BadTokenException ("token null is not valid", caught on-device 2026-08-27), so on
 * Infinix/TECNO/itel the overlay is a bare [WindowManager.addView] with a pre-draw
 * presentation listener instead. Both mechanisms reuse one window per display.
 */
class OverlayController(
    private val appContext: Context,
    private val stateMachine: ForegroundStateMachine,
    private val profiler: LatencyProfiler,
    private val onOpenRecovery: (ruleId: String) -> Unit,
    private val onDifferentSite: (browserPackage: String) -> Unit,
    /** Path B's host (enforcement plan v2 §8 Step 5): the foreground service holds no
     *  accessibility token, so its overlay is always the bare-addView
     *  TYPE_APPLICATION_OVERLAY mechanism (SYSTEM_ALERT_WINDOW-gated, Decision 16). */
    applicationOverlayHost: Boolean = false,
) {
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val dialogs = ConcurrentHashMap<Int, BlockDialog>()
    private val dialogViews = ConcurrentHashMap<Int, BlockOverlayView>()
    private val views = ConcurrentHashMap<Int, BlockOverlayView>()
    private val shown = ConcurrentHashMap<Int, Boolean>()
    private val contents = ConcurrentHashMap<Int, OverlayContent>()
    private val main = Handler(Looper.getMainLooper())

    private val useAddView = usesApplicationOverlay(applicationOverlayHost, isTranssion())

    /** The persistent walls' Close action (daily wall + app-block card) exists only where the
     *  host can evict the app behind the wall (see [dismissWall]) — i.e. the Accessibility
     *  host, whatever window mechanism it ends up using on this OEM. */
    private val canEvict: Boolean = !applicationOverlayHost

    /** §12 device-matrix fill-in: (manufacturer, sdkInt-or-null) entries whose non-focusable
     *  overlay routes touches through to the app beneath. Empty at launch; the §12 test suite
     *  populates it per the OEM touch-passthrough exception. */
    private val takeFocusOverrides: Set<Pair<String, Int?>> = emptySet()

    fun isShown(displayId: Int): Boolean = shown[displayId] == true

    /** What the display's visible card shows, or null when nothing is shown. */
    fun currentContent(displayId: Int): OverlayContent? =
        contents[displayId].takeIf { shown[displayId] == true }

    /** Create the primary display's overlay window at service connect, so the first block
     *  pays steady-state cost instead of one-time view inflation + WMS negotiation
     *  (caught on-device 2026-08-27: first-cycle submission ~980ms vs ~55ms steady-state —
     *  that cold first show was pinning the §2 presentation/submission p99). */
    fun prewarm() {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        runCatching { if (useAddView) viewFor(display.displayId) else dialogFor(display.displayId) }
    }

    /** [onAttached] fires once the window is confirmed drawable (first pre-draw after attach,
     *  or the next frame when re-binding an attached window) — §8 Step 4's eviction runs from
     *  it, never from the assumption that the add/show call succeeded. */
    fun show(
        generation: Long,
        displayId: Int,
        bounds: Rect,
        content: OverlayContent,
        onAttached: (() -> Unit)? = null,
    ) {
        if (!stateMachine.isCurrent(generation)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "show skipped gen=$generation display=$displayId gate=stale-generation")
            }
            return
        }
        val current = if (shown[displayId] == true) contents[displayId] else null
        if (current != null) {
            when {
                // Warning → warning (level 2 shadows level 1) or warning → block (the app
                // exhausted its last minutes while the banner was up): rebind in place — the
                // window stays attached, its flags may change (§8 Step 4), and a rebind of an
                // attached window is the only way the attach-confirm callback fires again for
                // the new content.
                current is OverlayContent.Warning -> rebindShown(displayId, bounds, content, onAttached)
                // A banner never replaces a wall (§8 Step 10).
                content is OverlayContent.Warning -> Unit
                // Block → block for the SAME scene keeps the device-verified first-wins
                // behavior: re-bound only (the first show passes display bounds; the
                // service's per-window pass must still land, or the cover freezes at
                // whole-display size and swallows the allowed pane — emulator-5554
                // split-screen, 2026-09-14). A DIFFERENT block target under the same window
                // must rebind its content: the card names what it covers (a blocked app
                // reached from the notification shade while another app's card is up).
                else -> if (current == content) refreshBounds(displayId, bounds)
                else rebindShown(displayId, bounds, content, onAttached)
            }
            return
        }
        contents[displayId] = content
        if (useAddView) showAddView(generation, displayId, bounds, content, onAttached) else showDialog(generation, displayId, bounds, content, onAttached)
    }

    /** Live re-bound of an already-shown overlay: updateViewLayout on the added view,
     *  window-attribute re-apply on the dialog. */
    private fun refreshBounds(displayId: Int, bounds: Rect) {
        if (useAddView) {
            val view = views[displayId] ?: return
            applyBounds(view, bounds)
            runCatching { windowManagerFor(displayId).updateViewLayout(view, view.layoutParams) }
        } else {
            dialogs[displayId]?.let { applyBounds(it, bounds) }
        }
    }

    /** Re-bound of an attached window with different content (warning → warning/block):
     *  re-render, re-bound, re-apply per-content flags, and re-arm the warning dismiss timer
     *  when still a warning. */
    private fun rebindShown(displayId: Int, bounds: Rect, content: OverlayContent, onAttached: (() -> Unit)?) {
        contents[displayId] = content
        if (useAddView) {
            val view = views[displayId] ?: return
            view.bind(content, onAttached)
            applyBounds(view, bounds)
            applyContentFlags(view.layoutParams as WindowManager.LayoutParams, content)
            runCatching { windowManagerFor(displayId).updateViewLayout(view, view.layoutParams) }
        } else {
            val dialog = dialogs[displayId] ?: return
            dialogViews[displayId]?.bind(content, onAttached)
            applyBounds(dialog, bounds)
            dialog.window?.let { window ->
                val lp = window.attributes
                applyContentFlags(lp, content)
                window.attributes = lp
            }
        }
        if (content is OverlayContent.Warning) scheduleWarningDismiss(displayId)
    }

    /** §8 Step 4: the warning overlay auto-dismisses after 5 seconds (tap dismiss is wired
     *  through the view's [BlockOverlayView] click contract). Instance identity guards a timer
     *  armed for an older banner from dismissing whatever replaced it — e.g. the limit block
     *  that upgrade-rebinds within the 5s window. */
    private fun scheduleWarningDismiss(displayId: Int) {
        val content = contents[displayId] ?: return
        main.postDelayed({
            if (contents[displayId] === content && content is OverlayContent.Warning) {
                hide(stateMachine.currentGeneration, displayId)
            }
        }, WARNING_DISMISS_MS)
    }

    fun hide(generation: Long, displayId: Int) {
        if (!stateMachine.isCurrent(generation)) {
            Log.i(TAG, "hide skipped gen=$generation display=$displayId gate=stale-generation curGen=${stateMachine.currentGeneration}")
            return
        }
        if (useAddView) hideAddView(generation, displayId) else hideDialog(generation, displayId)
    }

    /** Hides every display's overlay (screen-off/keyguard path, §12 life-safety). */
    fun hideAll(generation: Long) {
        shown.keys.toList().forEach { hide(generation, it) }
    }

    /** The bar-free display area (both hosts' full-screen cards use it): a full-display
     *  overlay draws above the navigation bar and swallows its touches, so Home/Back stop
     *  working (device-caught 2026-09-13 — the card's own hint promised they always work). */
    @Suppress("DEPRECATION") // getRealMetrics: deprecated in API 34, but the only non-cutout-aware path below API 30 (minSdk 24).
    fun displayBounds(displayId: Int): Rect {
        val display = displayManager.getDisplay(displayId) ?: return Rect()
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val full = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return full
        val bars = appContext.createDisplayContext(display)
            .getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.windowInsets
            ?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            ?: return full
        return Rect(
            full.left + bars.left,
            full.top + bars.top,
            full.right - bars.right,
            full.bottom - bars.bottom,
        )
    }

    /** Re-apply every visible card's geometry after a display configuration change (rotation,
     *  resolution). Both window mechanisms size the window from the bounds passed at show
     *  time; the window does not follow a later rotation, so a wall that was up across one
     *  stayed laid out for the old orientation (S49-E3 AVD, 2026-10-23: a landscape wall
     *  2,400 px wide on a 1,080 px portrait display, its text and Close off-screen). */
    fun refreshDisplayBounds() {
        shown.keys.toList().forEach { displayId ->
            val content = contents[displayId] ?: return@forEach
            refreshBounds(
                displayId,
                if (content is OverlayContent.Warning) warningBounds(displayId) else displayBounds(displayId),
            )
            Log.i(TAG, "display change: rebound display=$displayId content=${content::class.simpleName}")
        }
    }

    /** The warning banner occupies the top strip of the bar-free display area (§8 Step 10). */
    fun warningBounds(displayId: Int): Rect {
        val bounds = displayBounds(displayId)
        val height = (WARNING_BANNER_DP * appContext.resources.displayMetrics.density).toInt()
        return Rect(bounds.left, bounds.top, bounds.right, bounds.top + height)
    }

    /** Tap-dismiss for a warning banner (the view's click contract calls this). Only a
     *  banner yields — never a block card. */
    private fun dismissWarning(displayId: Int) {
        if (shown[displayId] == true && contents[displayId] is OverlayContent.Warning) {
            hide(stateMachine.currentGeneration, displayId)
        }
    }

    /** The persistent walls' Close action — daily wall (user ruling 2026-09-27) and app-block
     *  card (owner ruling 2026-10-03): the wall stays until the user dismisses it. Only the
     *  Accessibility host carries the action: that host evicts the app first, so lifting the
     *  wall only reveals what the user left behind. Path B's host cannot evict (no
     *  accessibility token), so an in-place dismiss there would hand the blocked app straight
     *  back — its wall still follows the foreground instead. Both wall kinds must be accepted
     *  here: the app-block card renders the same Close, and a DailyLimitBlocked-only gate left
     *  that button dead on the X6532 (device-caught 2026-10-03 — tap produced no dismissal). */
    private fun dismissWall(displayId: Int) {
        val content = contents[displayId]
        if (shown[displayId] == true &&
            (content is OverlayContent.DailyLimitBlocked || content is OverlayContent.AppBlocked)
        ) {
            hide(stateMachine.currentGeneration, displayId)
        }
    }

    /** Debug seam for the device suite: taps the live wall's real Close listener. False when
     *  no dismissible wall is shown, so "no wall" can never read as a successful dismissal. */
    internal fun debugClickDismiss(displayId: Int): Boolean {
        val content = contents[displayId]
        if (shown[displayId] != true) return false
        if (content !is OverlayContent.DailyLimitBlocked && content !is OverlayContent.AppBlocked) return false
        // The wall lives in `views` only on the addView host; stock Android's accessibility
        // host uses the Dialog path and keeps it in `dialogViews`. Reading `views` alone made
        // every dismissal on that host a false "no standing wall" (AVD, 2026-10-22), and the
        // leftover wall re-evicted every later launch with no decision.
        return (views[displayId] ?: dialogViews[displayId])?.debugClickDismiss() == true
    }

    /** Called from the service's onDestroy; overlay windows are system-owned once the
     *  accessibility service unbinds, this just releases our references promptly. */
    fun detachAll() {
        main.removeCallbacksAndMessages(null)
        dialogs.values.forEach { runCatching { it.hide() } }
        dialogs.clear()
        views.forEach { (id, view) -> if (view.isAttachedToWindow) runCatching { windowManagerFor(id).removeView(view) } }
        views.clear()
        shown.clear()
        contents.clear()
    }

    // --- stock Android: Dialog + FrameMetrics (plan AD 11.2) -----------------------------

    private fun showDialog(
        generation: Long,
        displayId: Int,
        bounds: Rect,
        content: OverlayContent,
        onAttached: (() -> Unit)?,
    ) {
        val dialog = dialogFor(displayId) ?: return
        try {
            dialogViews[displayId]?.bind(content, onAttached)
            applyBounds(dialog, bounds)
            dialog.window?.let { window ->
                val lp = window.attributes
                applyContentFlags(lp, content)
                window.attributes = lp
            }
            dialog.show()
            shown[displayId] = true
            profiler.recordSubmission(generation, displayId, SystemClock.elapsedRealtimeNanos())
            if (content is OverlayContent.Warning) scheduleWarningDismiss(displayId)
        } catch (t: Throwable) {
            Log.e(TAG, "overlay show failed category=${categorizeShowFailure(t)} display=$displayId gen=$generation", t)
            shown[displayId] = false
            runCatching { dialog.hide() }
        }
    }

    private fun hideDialog(generation: Long, displayId: Int) {
        val dialog = dialogs[displayId] ?: return
        try {
            dialog.hide()
        } catch (t: Throwable) {
            Log.w(TAG, "overlay hide failed display=$displayId gen=$generation", t)
        } finally {
            shown[displayId] = false
        }
    }

    private fun dialogFor(displayId: Int): BlockDialog? {
        dialogs[displayId]?.let { return it }
        val display = displayManager.getDisplay(displayId) ?: return null
        val displayContext = appContext.createDisplayContext(display)
        val dialog = BlockDialog(displayContext)
        // Install the decor BEFORE touching window attributes: setContentView builds the
        // DecorView and re-applies the theme's windowAnimationStyle (and the dim/background)
        // over anything written earlier. A `windowAnimations = 0` write made before this
        // point was silently reverted to the dialog theme's entrance animation — the window
        // then stalled drawn-but-transparent on the stock emulator (emulator-caught
        // 2026-09-13: WM said HAS_DRAWN + isVisible + topmost, zero pixels until window
        // animations were disabled). Attribute writes must come after this line.
        val view = BlockOverlayView(
            displayContext,
            { },
            onOpenRecovery,
            onDifferentSite,
            onWallDismiss = if (canEvict) ({ dismissWall(displayId) }) else null,
            onWarningDismiss = { dismissWarning(displayId) },
        )
        dialog.setContentView(view)
        val window = dialog.window ?: return null
        val lp = window.attributes
        // Creation-time default; every show re-applies the per-content flags below.
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // A plain Dialog's theme background is an inset rounded shape with a dim layer: the
        // opaque card rendered inside it, shrunk to a floating panel with the blocked app
        // visible around the edges (emulator-caught 2026-09-13; the XOS addView path has no
        // decor, so its card always filled the window). The Dialog path drops the theme
        // background and dim so the view fills the window exactly like the addView path;
        // both paths can't share the mechanism otherwise. No window animation either — the
        // bridge must appear instantly, not scale in.
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        lp.dimAmount = 0f
        lp.windowAnimations = 0
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ALWAYS (value 3, stable since API 28) is annotated @RequiresApi(30) in modern
            // stubs because R narrowed it to fullscreen windows; both behaviors are wanted
            // here (P-R: unconditional cutout extension), so the P-guard stands.
            @Suppress("InlinedApi")
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (shouldTakeFocus(Build.MANUFACTURER, Build.VERSION.SDK_INT, takeFocusOverrides)) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        window.attributes = lp
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialogViews[displayId] = view
        profiler.attach(window, displayId)
        dialogs[displayId] = dialog
        return dialog
    }

    private fun applyBounds(dialog: BlockDialog, bounds: Rect) {
        val window = dialog.window ?: return
        val lp = window.attributes
        lp.width = bounds.width()
        lp.height = bounds.height()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = bounds.left
        lp.y = bounds.top
        window.attributes = lp
    }

    private class BlockDialog(context: Context) : Dialog(context) {
        init {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
    }

    // --- Transsion XOS: bare addView (token-null Dialog windows rejected, 2026-08-27) ----

    private fun showAddView(
        generation: Long,
        displayId: Int,
        bounds: Rect,
        content: OverlayContent,
        onAttached: (() -> Unit)?,
    ) {
        val view = viewFor(displayId) ?: return
        try {
            view.bind(content, onAttached)
            applyBounds(view, bounds)
            applyContentFlags(view.layoutParams as WindowManager.LayoutParams, content)
            val wm = windowManagerFor(displayId)
            if (view.isAttachedToWindow) wm.updateViewLayout(view, view.layoutParams)
            else wm.addView(view, view.layoutParams)
            shown[displayId] = true
            profiler.recordSubmission(generation, displayId, SystemClock.elapsedRealtimeNanos())
            if (content is OverlayContent.Warning) scheduleWarningDismiss(displayId)
            Log.i(TAG, "shown display=$displayId gen=$generation curGen=${stateMachine.currentGeneration}")
        } catch (t: Throwable) {
            Log.e(TAG, "overlay show failed category=${categorizeShowFailure(t)} display=$displayId gen=$generation", t)
            shown[displayId] = false
            runCatching { if (view.isAttachedToWindow) windowManagerFor(displayId).removeView(view) }
        }
    }

    private fun hideAddView(generation: Long, displayId: Int) {
        val view = views[displayId] ?: return
        try {
            if (view.isAttachedToWindow) windowManagerFor(displayId).removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay hide failed display=$displayId gen=$generation", t)
        } finally {
            shown[displayId] = false
            Log.i(TAG, "hidden display=$displayId gen=$generation curGen=${stateMachine.currentGeneration}")
        }
    }

    private fun viewFor(displayId: Int): BlockOverlayView? {
        views[displayId]?.let { return it }
        val display = displayManager.getDisplay(displayId) ?: return null
        val displayContext = appContext.createDisplayContext(display)
        val view = BlockOverlayView(
            displayContext,
            { profiler.presented(displayId) },
            onOpenRecovery,
            onDifferentSite,
            onWallDismiss = if (canEvict) ({ dismissWall(displayId) }) else null,
            onWarningDismiss = { dismissWarning(displayId) },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ALWAYS (value 3, stable since API 28) is annotated @RequiresApi(30) in modern
            // stubs because R narrowed it to fullscreen windows; both behaviors are wanted
            // here (P-R: unconditional cutout extension), so the P-guard stands.
            @Suppress("InlinedApi")
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (shouldTakeFocus(Build.MANUFACTURER, Build.VERSION.SDK_INT, takeFocusOverrides)) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        view.layoutParams = lp
        views[displayId] = view
        return view
    }

    /** XOS rejects TYPE_ACCESSIBILITY_OVERLAY outright: token-null BadToken, then with a
     *  real activity token "token ... is not valid; is your activity running?" (2026-08-27) —
     *  it only accepts tokens of resumed activities, impossible while a blocked app is on
     *  top. The Transsion path uses TYPE_APPLICATION_OVERLAY instead, which WMS grants to
     *  any uid holding the SYSTEM_ALERT_WINDOW appop ("display over other apps"), no token. */
    private fun overlayWindowType(): Int =
        if (useAddView) {
            // TYPE_APPLICATION_OVERLAY replaced TYPE_PHONE at API 26 with the same value (2038).
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

    private fun windowManagerFor(displayId: Int): WindowManager {
        val display = displayManager.getDisplay(displayId)
            ?: return appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return appContext.createDisplayContext(display).getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun applyBounds(view: View, bounds: Rect) {
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.width = bounds.width()
        lp.height = bounds.height()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = bounds.left
        lp.y = bounds.top
    }

    /** Overwrite the window flags for the content about to be shown (§8 Steps 4/5), then
     *  re-apply the OEM focus exception. Runs on both hosts and on in-place rebinds — the
     *  flags differ per content, not per window. */
    private fun applyContentFlags(lp: WindowManager.LayoutParams, content: OverlayContent) {
        lp.flags = flagsForContent(content, debugAllowsCapture = BuildConfig.DEBUG)
        if (shouldTakeFocus(Build.MANUFACTURER, Build.VERSION.SDK_INT, takeFocusOverrides)) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
    }

    companion object {
        private const val TAG = "MyExodusOverlay"

        /** §8 Step 4: the warning banner auto-dismisses after 5 seconds. */
        private const val WARNING_DISMISS_MS = 5_000L

        /** Warning banner height in dp — the top strip that cannot swallow the app (§10). */
        private const val WARNING_BANNER_DP = 96

        /**
         * Per-content window flags (§8 Steps 4/10; pure for direct testing). Every card is
         * `FLAG_NOT_TOUCH_MODAL`: a touch-modal window leaves the system bars dead wherever
         * its region reaches — XOS sized the daily wall's input region to 3x the display, so
         * the physical Home/Back/Recents buttons were swallowed whole with no dismiss
         * affordance on the wall (device-caught 2026-09-27 on the X6532, user report: "I can't
         * press my phone's Home or back to dismiss the block screen"). Not-touch-modal keeps
         * every touch inside the card's bounds with the card, and hands the bar strips back to
         * SystemUI. The daily-limit block is additionally focusable (no `FLAG_NOT_FOCUSABLE`)
         * and carries `FLAG_SECURE` (Step 5: the block screen must not be
         * screenshot-capturable) — except with [debugAllowsCapture], the debug-build-only
         * visual-QA lift so the wall can be screencapped for review (user-approved 2026-09-15).
         * The app/URL cover keeps its device-verified non-focusable flags. Warnings are
         * non-focusable and touch-transparent outside their own bounds so the app underneath
         * stays usable, and the host auto-dismisses them.
         */
        fun flagsForContent(content: OverlayContent, debugAllowsCapture: Boolean = false): Int =
            when (content) {
                is OverlayContent.DailyLimitBlocked ->
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        (if (debugAllowsCapture) 0 else WindowManager.LayoutParams.FLAG_SECURE)
                is OverlayContent.Warning ->
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                else ->
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

        /** Transsion XOS rejects TYPE_ACCESSIBILITY_OVERLAY (2026-08-27); the OEM-keyed
         *  exception shows TYPE_APPLICATION_OVERLAY instead, which needs the
         *  SYSTEM_ALERT_WINDOW appop ("display over other apps"). */
        fun isTranssion(): Boolean =
            Build.MANUFACTURER.lowercase() in setOf("infinix", "tecno", "itel")

        /** Which window mechanism a host uses (pure for direct testing): Path B always, the
         *  accessibility host only on the Transsion exception. */
        fun usesApplicationOverlay(applicationOverlayHost: Boolean, transsion: Boolean): Boolean =
            applicationOverlayHost || transsion

        /** True when the overlay window type is usable; the settings screen uses it to
         *  prompt the grant on Transsion devices. */
        @Suppress("DEPRECATION") // unsafeCheckOpNoThrow: deprecated in API 34; checkOpNoThrow is gone in Q+ stubs, and minSdk 24 needs both branches.
        fun overlayPermissionGranted(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return@runCatching false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        /** Exception → category per plan §8 Step 5; pure for direct unit testing. */
        fun categorizeShowFailure(t: Throwable): String = when (t) {
            is WindowManager.BadTokenException -> "bad-token"
            is SecurityException -> "revoked-permission"
            is IllegalStateException -> "already-attached"
            is android.os.DeadObjectException -> "dead-system-server"
            is android.os.RemoteException -> "dead-system-server"
            else -> "unknown(${t.javaClass.simpleName})"
        }

        /** OEM touch-passthrough exception decision (plan §8 Step 5); pure for testing. */
        fun shouldTakeFocus(
            manufacturer: String?,
            sdkInt: Int,
            overrides: Set<Pair<String, Int?>>,
        ): Boolean = overrides.any { (m, sdk) ->
            m.equals(manufacturer, ignoreCase = true) && (sdk == null || sdk == sdkInt)
        }
    }
}
