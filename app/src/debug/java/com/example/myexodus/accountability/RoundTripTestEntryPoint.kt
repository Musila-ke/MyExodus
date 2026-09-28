package com.example.myexodus.accountability

import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.ui.partner.PartnerDashboardSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Test access to the round-trip harness's singletons (same pattern as
 * [PairingTestEntryPoint]): the instrumented test drives the exact instances the app's real
 * flows use — the real identity's pairing flow, signer, chain store, and sync coordinator —
 * never hand-wired copies.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RoundTripTestEntryPoint {
    fun pairingFlow(): PairingFlow
    fun signer(): AccountabilitySigner
    fun enrollmentDao(): EnrollmentDao
    fun chainStateStore(): ChainStateStore
    fun eventCipher(): EventCipher
    fun cekDerivation(): CekDerivation
    fun identityEpochStore(): IdentityEpochStore
    fun partnerSyncCoordinator(): PartnerSyncCoordinator

    /** The Step 12 dashboard read model — the exact DI-wired source the Partner tab renders. */
    fun partnerDashboardSource(): PartnerDashboardSource

    /** Step 13 (ADR #48) responder half, the exact DI-wired instance the approval UI calls. */
    fun approvalFlow(): ApprovalFlow

    /** §10/§19.5 (ADR #106) the unpair protocol — the exact DI-wired instance the Partner tab calls. */
    fun bilateralDeletion(): BilateralDeletion

    /** The real AndroidKeystore, for asserting what a shred did and did not destroy. */
    fun keyStore(): AccountabilityKeyStore
}
