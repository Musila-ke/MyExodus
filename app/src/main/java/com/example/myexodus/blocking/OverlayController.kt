package com.example.myexodus.blocking

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
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
) {
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val dialogs = ConcurrentHashMap<Int, BlockDialog>()
    private val dialogViews = ConcurrentHashMap<Int, BlockOverlayView>()
    private val views = ConcurrentHashMap<Int, BlockOverlayView>()
    private val shown = ConcurrentHashMap<Int, Boolean>()
    private val contents = ConcurrentHashMap<Int, OverlayContent>()

    private val useAddView = isTranssion()

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

    fun show(generation: Long, displayId: Int, bounds: Rect, content: OverlayContent) {
        if (!stateMachine.isCurrent(generation)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "show skipped gen=$generation display=$displayId gate=stale-generation")
            }
            return
        }
        if (shown[displayId] == true) {
            // Already shown: re-bound, never re-add. The first show passes display bounds
            // (cold-sub-window guard); the service's per-window pass that follows must still
            // land, or the cover freezes at whole-display size and swallows the allowed pane
            // (emulator-5554 split-screen, 2026-09-14). Content stays first-wins.
            refreshBounds(displayId, bounds)
            return
        }
        contents[displayId] = content
        if (useAddView) showAddView(generation, displayId, bounds, content) else showDialog(generation, displayId, bounds, content)
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

    /** Called from the service's onDestroy; overlay windows are system-owned once the
     *  accessibility service unbinds, this just releases our references promptly. */
    fun detachAll() {
        dialogs.values.forEach { runCatching { it.hide() } }
        dialogs.clear()
        views.forEach { (id, view) -> if (view.isAttachedToWindow) runCatching { windowManagerFor(id).removeView(view) } }
        views.clear()
        shown.clear()
        contents.clear()
    }

    // --- stock Android: Dialog + FrameMetrics (plan AD 11.2) -----------------------------

    private fun showDialog(generation: Long, displayId: Int, bounds: Rect, content: OverlayContent) {
        val dialog = dialogFor(displayId) ?: return
        try {
            dialogViews[displayId]?.bind(content)
            applyBounds(dialog, bounds)
            dialog.show()
            shown[displayId] = true
            profiler.recordSubmission(generation, displayId, SystemClock.elapsedRealtimeNanos())
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
        val view = BlockOverlayView(displayContext, { }, onOpenRecovery, onDifferentSite)
        dialog.setContentView(view)
        val window = dialog.window ?: return null
        val lp = window.attributes
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

    private fun showAddView(generation: Long, displayId: Int, bounds: Rect, content: OverlayContent) {
        val view = viewFor(displayId) ?: return
        try {
            view.bind(content)
            applyBounds(view, bounds)
            val wm = windowManagerFor(displayId)
            if (view.isAttachedToWindow) wm.updateViewLayout(view, view.layoutParams)
            else wm.addView(view, view.layoutParams)
            shown[displayId] = true
            profiler.recordSubmission(generation, displayId, SystemClock.elapsedRealtimeNanos())
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
        val view = BlockOverlayView(displayContext, { profiler.presented(displayId) }, onOpenRecovery, onDifferentSite)
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

    companion object {
        private const val TAG = "MyExodusOverlay"

        /** Transsion XOS rejects TYPE_ACCESSIBILITY_OVERLAY (2026-08-27); the OEM-keyed
         *  exception shows TYPE_APPLICATION_OVERLAY instead, which needs the
         *  SYSTEM_ALERT_WINDOW appop ("display over other apps"). */
        fun isTranssion(): Boolean =
            Build.MANUFACTURER.lowercase() in setOf("infinix", "tecno", "itel")

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
