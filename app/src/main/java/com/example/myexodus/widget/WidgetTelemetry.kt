package com.example.myexodus.widget

import android.content.Context
import com.example.myexodus.telemetry.CrashReporting
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance instantiates [DesertDawnWidget] itself, so it cannot be `@AndroidEntryPoint` — this entry
 * point is how the widget process path reaches the app's telemetry (spec §14).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetTelemetryEntryPoint {
    fun crashReporting(): CrashReporting
}

internal fun widgetTelemetry(context: Context): CrashReporting =
    EntryPointAccessors.fromApplication(context, WidgetTelemetryEntryPoint::class.java)
        .crashReporting()
