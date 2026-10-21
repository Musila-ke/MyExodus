package com.example.myexodus.accountability

import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.monitoring.MonitoringSnapshotRecorder
import com.example.myexodus.accountability.monitoring.PartnerHeartbeatClient
import com.example.myexodus.accountability.sos.NudgeInboxRepository
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.sync.PairUidResolver
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.data.db.AppRulesStoreFactory
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

    /** Step 6 crash-window scenario: seed the processed-events gate exactly as a process death
     *  between the chain commit and the ack leaves it. */
    fun accountabilityDatabase(): AccountabilityDatabase

    /** The Step 12 dashboard read model — the exact DI-wired source the Partner tab renders. */
    fun partnerDashboardSource(): PartnerDashboardSource

    /** Step 13 (ADR #48) responder half, the exact DI-wired instance the approval UI calls. */
    fun approvalFlow(): ApprovalFlow

    /** §10/§19.5 (ADR #106) the unpair protocol — the exact DI-wired instance the Partner tab calls. */
    fun bilateralDeletion(): BilateralDeletion

    /** The real AndroidKeystore, for asserting what a shred did and did not destroy. */
    fun keyStore(): AccountabilityKeyStore

    /** SOS §12.3: the DI-wired recipient inbox the Home surface's nudge card renders from. */
    fun nudgeInboxRepository(): NudgeInboxRepository

    /** §7 Step 7: the exact heartbeat client + install registrar the FGS and sync worker use. */
    fun partnerHeartbeatClient(): PartnerHeartbeatClient
    fun pushInstallationRegistrar(): PushInstallationRegistrar

    /** §7 Step 7 sender half: the exact recorder the FGS runs, for the device consent leg. */
    fun monitoringSnapshotRecorder(): MonitoringSnapshotRecorder

    /** The production uid resolver — the device leg picks a pair whose server pairing exists. */
    fun pairUidResolver(): PairUidResolver

    /** Reads the persisted counter/standby row the device proofs assert against. */
    fun appRulesStoreFactory(): AppRulesStoreFactory
}
