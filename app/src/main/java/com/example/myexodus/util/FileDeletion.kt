package com.example.myexodus.util

import android.util.Log
import java.io.File

/** Best-effort delete whose failure is recorded instead of silently dropped (Sonar S899). */
fun File.deleteOrWarn(tag: String) {
    if (!delete() && exists()) Log.w(tag, "cleanup delete failed: $path")
}
