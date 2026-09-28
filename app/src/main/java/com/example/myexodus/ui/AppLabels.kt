package com.example.myexodus.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Default size for [AppMark]; rule rows derive their divider inset from it so text stays aligned. */
val APP_MARK_SIZE = 36.dp

/**
 * Best-effort display name for a blocked target: the installed app's label when the package
 * is visible to us, else the raw identifier (package name or domain). Used by the block
 * overlay, the block screen, and the home dashboard block list.
 */
fun appLabel(context: Context, target: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(target, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrElse { target }

@Composable
fun rememberAppLabel(target: String): String {
    val context = LocalContext.current
    return remember(target) { appLabel(context, target) }
}

/**
 * The leading mark for a blocked target: the installed app's own launcher icon, else [fallback]
 * (an uninstalled package, or a website rule, where there is no icon to read). The mark is
 * decorative wherever it is used — the target's name is always text beside it — so it carries
 * no content description of its own.
 *
 * `PackageManager.getApplicationIcon` is main-thread work done once per rule, not per frame;
 * a list long enough for that to show up wants a shared bitmap cache, not a bigger mark.
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
