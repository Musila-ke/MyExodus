package com.example.myexodus.blocking

import android.content.Context

/**
 * `RoleManager.getRoleHolders` is absent from the compileSdk stubs (ADR #17); invoke it
 * reflectively so runtimes that ship it (API 29+) stay covered. Empty on any failure.
 */
internal fun roleHolders(context: Context, role: String): List<String> = runCatching {
    // ROLE_SERVICE is API 29; getSystemService(unknown service) returns null below it,
    // which is exactly what the reflective fallback expects.
    @Suppress("InlinedApi")
    val roleManager = context.getSystemService(Context.ROLE_SERVICE)
        ?: return@runCatching emptyList()
    val method = roleManager.javaClass.getMethod("getRoleHolders", String::class.java)
    @Suppress("UNCHECKED_CAST")
    method.invoke(roleManager, role) as? List<String> ?: emptyList()
}.getOrDefault(emptyList())
