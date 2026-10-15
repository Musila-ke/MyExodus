package com.example.myexodus.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default size for [AppMark]; rule rows derive their divider inset from it so text stays aligned. */
val APP_MARK_SIZE = 36.dp

/**
 * Best-effort display name for a blocked target: the installed app's label when the package
 * is visible to us, else the raw identifier (package name or domain). Blocking calls run on
 * the caller's thread — use [rememberAppLabel] for anything on the composition path.
 */
fun appLabel(context: Context, target: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(target, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrElse { target }

// Resolved labels, shared process-wide. Snapshot-backed so a background write triggers
// recomposition of every row reading it; a miss is instant (never a PackageManager binder
// call on the main thread — the 4000-rule Home ANR was exactly that, task #13).
private val labelCache = mutableStateMapOf<String, String>()

// Targets whose load is in flight or done, so N rows of the same list don't queue N loads.
private val labelLoads = ConcurrentHashMap.newKeySet<String>()

// At most 4 parallel PackageManager lookups: enough to drain a 4000-target list quickly,
// bounded so a long list can't flood the system server with binder traffic.
internal val appLabelLoader: CoroutineScope =
    CoroutineScope(Dispatchers.IO.limitedParallelism(4))

internal fun peekAppLabel(target: String): String? = labelCache[target]

/**
 * Publishes a label a caller already resolved synchronously (both block-fire paths name the
 * target for the overlay card before the durable BlockerActivity launches): the activity then
 * reads the same string from its first composition instead of flashing the raw identifier on
 * a cold cache (task #4 audit — the async pipeline's miss path above is for lists, which can
 * afford it; a block screen's title cannot).
 */
internal fun seedAppLabel(target: String, label: String) {
    labelLoads.add(target)
    Snapshot.withMutableSnapshot { labelCache[target] = label }
}

/** Label for a target: cached if known, else null and a background load is queued. */
internal fun cachedAppLabel(
    target: String,
    context: Context,
    load: CoroutineScope,
    resolve: suspend (String) -> String = { appLabel(context, it) },
): String? {
    labelCache[target]?.let { return it }
    if (labelLoads.add(target)) {
        load.launch {
            val label = resolve(target)
            Snapshot.withMutableSnapshot { labelCache[target] = label }
        }
    }
    return null
}

/**
 * One-shot warm-up of many targets (the 4000-rule ceiling case): each resolved label lands
 * in the shared cache as it completes, so rows recompose progressively instead of paying a
 * synchronous PackageManager call per row mid-composition.
 */
suspend fun preloadAppLabels(
    context: Context,
    targets: Collection<String>,
    resolve: suspend (String) -> String = { appLabel(context, it) },
) {
    val pending = targets.asSequence().filter { labelLoads.add(it) }.toList()
    if (pending.isEmpty()) return
    // Recomposition between loads is the point (labels land progressively), so no batching.
    withContext(Dispatchers.IO.limitedParallelism(4)) {
        pending.forEach { target ->
            val label = resolve(target)
            Snapshot.withMutableSnapshot { labelCache[target] = label }
        }
    }
}

/**
 * [appLabel] without the main-thread binder cost: the cached label when known, else the raw
 * identifier immediately (package name or domain — the same fallback as an uninstalled app)
 * with the real label arriving on a later composition. The one real cost of the async path
 * is that a cold process shows identifiers for the first frames; a warm cache shows labels
 * at once.
 */
@Composable
fun rememberAppLabel(target: String): String {
    val context = LocalContext.current
    val cached = labelCache[target]
    if (cached == null) {
        LaunchedEffect(target) { cachedAppLabel(target, context, appLabelLoader) }
    }
    return cached ?: target
}

/**
 * The leading mark for a blocked target: the installed app's own launcher icon, else [fallback]
 * (an uninstalled package, or a website rule, where there is no icon to read). The mark is
 * decorative wherever it is used — the target's name is always text beside it — so it carries
 * no content description of its own.
 *
 * Synchronous on first composition, but only for rows that actually compose (lazy lists), so
 * it scales with the visible rows, not the rule count.
 */
@Composable
fun AppMark(
    packageName: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = APP_MARK_SIZE,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(packageName, sizePx) {
        packageName?.let {
            runCatching {
                context.packageManager.getApplicationIcon(it).toBitmap(sizePx, sizePx).asImageBitmap()
            }.getOrNull()
        }
    }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        Icon(
            fallback,
            contentDescription = null,
            tint = fallbackTint,
            modifier = modifier.size(size),
        )
    }
}
