package com.example.myexodus.update

import android.content.Context
import android.os.Build
import com.example.myexodus.BuildConfig

internal fun installedVersionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}.getOrDefault(BuildConfig.VERSION_CODE.toLong())
