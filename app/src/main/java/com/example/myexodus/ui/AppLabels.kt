package com.example.myexodus.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
