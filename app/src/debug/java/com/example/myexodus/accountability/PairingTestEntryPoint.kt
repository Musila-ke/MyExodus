package com.example.myexodus.accountability

import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Test access to the device keystore so the on-device harness signs with the exact keypair
 * the app's real flows use. The pairing graph itself is built inside the activity against a
 * named Firebase app (ADR #209) — the entry point deliberately exposes no production
 * identity, transport, or enrollment instances.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PairingTestEntryPoint {
    fun keystore(): AccountabilityKeyStore
}
