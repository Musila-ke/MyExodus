package com.example.myexodus.accountability.sync

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Test access to the Step-7 FCM transport singleton (AccountabilityModule) — the instrumented
 * device test drives the exact registrar instance the worker uses (same process, same graph),
 * not a hand-wired copy.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PartnerTransportTestEntryPoint {
    fun pushInstallationRegistrar(): PushInstallationRegistrar
}
