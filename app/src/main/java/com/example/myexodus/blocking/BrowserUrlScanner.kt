package com.example.myexodus.blocking

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * In-browser URL scanning (Enhanced Mode spec §8 Steps 7–8, Data Flow step 6): content-changed
 * events on browsers feed a 300ms debounce, then the address-bar node is extracted by view-ID,
 * canonicalized, and run through the [BlockDecisionEngine]. [viewIdFor] discovers a browser by
 * probing a small set of known toolbar view-IDs in its own tree — a package allowlist can only
 * cover browsers someone listed, and real devices carry arbitrary ones (user-raised
 * 2026-09-13). The generation captured at event time gates the debounced scan at result time —
 * a newer transition discards the result (stale-event protection via the state machine, plan
 * §9). The window root is resolved at fire time, not captured at event time: the content-changed
 * source node is the page subtree, which never contains the toolbar's address bar (observed
 * on-device 2026-08-27 — every scan came back empty until the root was resolved at fire time).
 * A view-ID lookup failure (browser update renamed the ID) is a silent no-op with a diagnostic
 * log — never a block, never a crash. Address-bar text that is not a page address (mid-typing
 * fragments, the omnibox's own hint) is also a no-op: deciding it would dismiss a live card
 * over a still-blocked page (device-caught 2026-09-13).
 */
class BrowserUrlScanner(
    private val engine: BlockDecisionEngine,
    private val stateMachine: ForegroundStateMachine,
    private val onResult: (generation: Long, decision: BlockDecision, canonical: CanonicalUrl?, packageName: String) -> Unit,
    private val extractUrlText: (root: AccessibilityNodeInfo?, viewId: String) -> String? = { root, viewId ->
        defaultExtractUrlText(root, viewId)
    },
    private val hasView: (root: AccessibilityNodeInfo, viewId: String) -> Boolean = { root, viewId ->
        root.findAccessibilityNodeInfosByViewId(viewId).isNotEmpty()
    },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    // Main-thread only (accessibility dispatch + the scanner's handler post there).
    private val viewIdByPackage = HashMap<String, String>()
    private val lastProbeAt = HashMap<String, Long>()

    /**
     * The package's address-bar view-ID, discovered on first sight: the existence of any known
     * toolbar ID in the package's own tree marks it a browser and names its bar — nothing to
     * maintain per browser package. A failed probe is retried after [PROBE_RETRY_MS], so a
     * browser whose toolbar was absent at probe time (fullscreen video, first paint) cannot
     * stay invisible for the process lifetime — a silent bypass of every URL rule. Probes run
     * only against the package's own tree: a background package's content change must not be
     * judged (and mis-cached) by whichever app is in the foreground. The root provider is not
     * invoked on memo hits, keeping the content-changed hot path free of tree lookups.
     */
    fun viewIdFor(packageName: String, root: () -> AccessibilityNodeInfo?): String? {
        viewIdByPackage[packageName]?.let { return it }
        val now = SystemClock.uptimeMillis()
        val probedAt = lastProbeAt[packageName]
        if (probedAt != null && now - probedAt < PROBE_RETRY_MS) return null
        val tree = root() ?: return null
        val treePackage = tree.packageName?.toString()
        if (treePackage != null && treePackage != packageName) return null
        lastProbeAt[packageName] = now
        val found = ADDRESS_BAR_IDS
            .map { "$packageName:id/$it" }
            .firstOrNull { hasView(tree, it) } ?: return null
        Log.d(TAG, "address bar discovered: $packageName -> $found")
        viewIdByPackage[packageName] = found
        return found
    }

    fun onContentChanged(
        packageName: String,
        viewId: String,
        activeWindowRoot: () -> AccessibilityNodeInfo?,
        generation: Long,
    ) {
        if (!stateMachine.isCurrent(generation)) return
        pending?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pending = null
            if (!stateMachine.isCurrent(generation)) return@Runnable
            val text = extractUrlText(activeWindowRoot(), viewId) ?: run {
                Log.d(TAG, "url scan no-op: no address-bar text for $packageName ($viewId)")
                return@Runnable
            }
            // Non-page text is not evidence (device-caught 2026-09-13): mid-typing fragments
            // ("ex") and the omnibox's own hint ("Search or type URL") canonicalize to a
            // dotless host, match nothing, and used to Allow — dismissing the card over a
            // still-blocked page. Only page-like text (explicit scheme or dotted host)
            // decides, unless a rule matches it outright. A scheme-prefixed non-URL
            // (about:blank — the "different site" target —, chrome://, file://) has no
            // canonical form and is a navigation, so it is always allowed.
            val canonical = UrlPattern.canonicalizeCandidate(text)
            val decision = when {
                canonical == null ->
                    if (SCHEME_PREFIX.containsMatchIn(text.trim())) BlockDecision.Allow
                    else {
                        Log.d(TAG, "url scan no-op: unparseable, not a scheme navigation '$text'")
                        return@Runnable
                    }
                !isPageLike(canonical) && engine.matchingUrlRule(canonical) == null -> {
                    Log.d(TAG, "url scan no-op: not a page-like address '$text'")
                    return@Runnable
                }
                else -> engine.decideUrl(canonical, System.currentTimeMillis())
            }
            if (com.example.myexodus.BuildConfig.DEBUG) {
                Log.i(TAG, "url scan gen=$generation '$text' -> ${decision.javaClass.simpleName}")
            }
            onResult(generation, decision, canonical, packageName)
        }
        pending = task
        handler.postDelayed(task, DEBOUNCE_MS)
    }

    /** Drop any pending scan (service disconnect); event handlers already discard stale runs. */
    fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private companion object {
        const val TAG = "MyExodusUrlScan"
        const val DEBOUNCE_MS = 300L

        /** Re-probe cadence for packages whose first probe showed no toolbar ([viewIdFor]). */
        const val PROBE_RETRY_MS = 10_000L

        /** Toolbar view-IDs probed across all packages (name, not full id — the package
         *  prefix comes from the event's package). Chrome/Brave/Chromium forks use `url_bar`,
         *  Firefox `url_bar_text`, Samsung Internet `location_bar_text`; a browser using a
         *  different ID needs it appended here (plan §16 ongoing maintenance). */
        val ADDRESS_BAR_IDS = listOf("url_bar", "url_bar_text", "location_bar_text")

        /** Any `scheme:` prefix — marks a navigation to a non-web page (no canonical form). */
        private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        /** A page address states its scheme or has a dotted host (registrable domain or IP). */
        private fun isPageLike(canonical: CanonicalUrl): Boolean =
            canonical.scheme != null || canonical.hostUnicode.contains('.')

        /** Platform walk; null when the view-ID is missing or has no text (silent no-op upstream). */
        private fun defaultExtractUrlText(root: AccessibilityNodeInfo?, viewId: String): String? {
            val matches = root?.findAccessibilityNodeInfosByViewId(viewId) ?: return null
            return matches.asSequence()
                .mapNotNull { it.text?.toString() }
                .firstOrNull { it.isNotBlank() }
        }
    }
}
