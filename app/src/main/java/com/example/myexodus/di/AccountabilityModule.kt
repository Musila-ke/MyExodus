package com.example.myexodus.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.room3.Room
import com.example.myexodus.accountability.BilateralDeletion
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.crypto.IdentityMigration
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentFlow
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ApprovalRequestDao
import com.example.myexodus.accountability.state.ChainEventDao
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.LocalWipeDetector
import com.example.myexodus.accountability.state.SessionKeyManager
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.ChainRepairCoordinator
import com.example.myexodus.accountability.sync.FirestoreMailboxReceiver
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.await
import com.example.myexodus.accountability.sync.MailboxEventProcessor
import com.example.myexodus.accountability.sync.MailboxMessageHandler
import com.example.myexodus.accountability.sync.MailboxOutboxDrainer
import com.example.myexodus.accountability.sync.MailboxRejectionChecker
import com.example.myexodus.accountability.sync.PUSH_REGISTRATION_CONFIG_KEY
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PairingRegistration
import com.example.myexodus.accountability.sync.PairingReachabilityStore
import com.example.myexodus.accountability.sync.PairUidResolver
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerLiveSync
import com.example.myexodus.accountability.sync.displayNameOf
import com.example.myexodus.accountability.activity.PartnerActivityJournal
import com.example.myexodus.accountability.activity.PartnerActivityReceiver
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.accountability.sync.PartnerSyncTelemetry
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.accountability.sync.decodePublicKey
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.decodeMilestoneDays
import com.example.myexodus.secrets.SecretsRepository
import com.example.myexodus.accountability.sos.NudgeInboxRepository
import com.example.myexodus.accountability.sos.NudgeRepository
import com.example.myexodus.accountability.sos.NudgeSettingsRepository
import com.example.myexodus.accountability.sync.PARTNER_SOS_CONFIG_KEY
import com.example.myexodus.ui.partner.PartnerActivityUi
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerSelection
import com.example.myexodus.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Base64
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * §7.5 wiring for the Accountability-Partner Protocol. The sync engines are provided as
 * singletons; UI consumption lands with the V1 accountability screens. FirebaseFirestore
 * resolves lazily (first injector wins) so the graph stays buildable until the
 * google-services.json + Firebase init step lands (§18).
 */
@Module
@InstallIn(SingletonComponent::class)
object AccountabilityModule {

    private const val TAG = "MyExodusAccountabilityModule"

    @Provides @Singleton
    fun provideAccountabilityDatabase(@ApplicationContext context: Context): AccountabilityDatabase =
        Room.databaseBuilder(context, AccountabilityDatabase::class.java, AccountabilityDatabase.DB_NAME)
            .addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideChainEventDao(db: AccountabilityDatabase): ChainEventDao = db.chainEventDao()

    @Provides
    fun provideEnrollmentDao(db: AccountabilityDatabase): EnrollmentDao = db.enrollmentDao()

    @Provides
    fun provideApprovalRequestDao(db: AccountabilityDatabase): ApprovalRequestDao = db.approvalRequestDao()

    @Provides @Singleton
    fun provideChainStateStore(db: AccountabilityDatabase, crashReporting: CrashReporting): ChainStateStore =
        ChainStateStore(db, onSecuritySignal = { event -> crashReporting.nonFatal(event) })

    /** §8 Step 15 (AD-12): rotation policy over the chain heads. */
    @Provides @Singleton
    fun provideSessionKeyManager(store: ChainStateStore): SessionKeyManager = SessionKeyManager(store)

    /** §8 Step 17 (AD-22, NFR7): the app-start wipe detector. The signal is the keystore
     *  agreement key — outside the database it must outlive, and destroyed only by the unpair
     *  shred (before its enrollment), so key-present + enrollment-absent is never a normal end.
     *
     *  The keystore is resolved lazily *inside* the signal lambda: it cannot be constructed on
     *  every surface this provider graphs into (JVM/Robolectric, and a broken keystore on a real
     *  device), and resolving there puts any such failure inside the detector's own
     *  signal-check guard instead of at app-startup injection. */
    @Provides @Singleton
    fun provideLocalWipeDetector(
        db: AccountabilityDatabase,
        keystore: Lazy<AccountabilityKeyStore>,
        store: ChainStateStore,
    ): LocalWipeDetector = LocalWipeDetector(
        hasPriorPairingSignal = { keystore.get().hasAgreementKey() },
        enrollments = db.enrollmentDao()::all,
        wipeOrphanPairData = store::wipeAllPairData,
    )

    @Provides @Singleton
    fun provideEventCipher(): EventCipher = EventCipher()

    @Provides @Singleton
    fun provideCekDerivation(): CekDerivation = CekDerivation()

    @Provides @Singleton
    fun provideMailboxEventProcessor(
        signer: AccountabilitySigner,
        eventCipher: EventCipher,
    ): MailboxEventProcessor = MailboxEventProcessor(signer, eventCipher)

    @Provides @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging = FirebaseMessaging.getInstance()

    /**
     * Plan §13 line 564: the push-registration rollout gate. In-code defaults are set
     * BEFORE the first read — Remote Config's in-app defaults are separate from console
     * values, so without this an unset console key would read `false` and silently disable a
     * working rail. The 15-minute minimum fetch interval matches the sync cadence: a console
     * flip is picked up by the next pass. A failed fetch must not discard the last activated
     * value, so it is swallowed and the read still serves the cached value (or the in-app
     * default on a fresh, offline install). SOS §8.20 rides the same instance: one defaults
     * map, one fetch — `partner_sos_enabled` defaults `false`, the opposite failure default
     * of a not-yet-released feature.
     */
    @Provides @Singleton
    fun providePartnerRolloutGate(): PartnerRolloutGate {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(900)
                .build(),
        )
        remoteConfig.setDefaultsAsync(
            mapOf(
                PUSH_REGISTRATION_CONFIG_KEY to true,
                PARTNER_SOS_CONFIG_KEY to false,
            ),
        )
        return PartnerRolloutGate { key ->
            runCatching { remoteConfig.fetchAndActivate().await() }
            remoteConfig.getBoolean(key)
        }
    }

    /**
     * SOS Step 8 (§6.1/§8.15, table A2): the Reach out sender path. The target pair follows
     * the §19.5 persisted selection (else the newest enrollment — the dashboard's own rule);
     * the CEK is the same in-memory derivation the dashboard's decoder uses; the callable
     * rides the shared region-pinned [FirebaseFunctions] instance. FR-3's preflight demands a
     * *validated* network (captive portals and dead links fail it), stricter than the
     * INTERNET-only check the sync pass uses — an offline tap must not fire a doomed callable.
     */
    @Provides @Singleton
    fun provideNudgeRepository(
        @ApplicationContext context: Context,
        db: AccountabilityDatabase,
        functions: FirebaseFunctions,
        keystore: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        uidResolver: PairUidResolver,
        partnerSelection: PartnerSelection,
        eventCipher: EventCipher,
    ): NudgeRepository = NudgeRepository(
        enrollments = db.enrollmentDao()::all,
        selectedPairId = { partnerSelection.selectedPairId },
        ensureUid = { uidResolver.ensureUid() },
        deriveCek = { enrollment ->
            cekDerivation.deriveCek(
                enrollment.pairId,
                keystore.agreementPrivateKeyFor(enrollment.myAgreementKeyAlias),
                decodePublicKey(enrollment.partnerAgreementPubKey),
            )
        },
        callSendNudge = { wire -> functions.getHttpsCallable("sendNudge").call(wire).await().data },
        validatedNetwork = {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        },
        cipher = eventCipher,
    )

    /**
     * SOS Step 9 (§6.9/§8.16): the recipient inbox. The listener carries the exact §6.9
     * query ([FirestoreSchema.nudgeInboxQuery]); the ack is one WriteBatch of the rules'
     * one allowed mutation ([FirestoreSchema.nudgeAckData], ≤10 writes against the 500
     * limit); notifications are cancelled by the repository only after that batch commits.
     * The unreadable-document report is the house telemetry contract — event + cause only
     * (§6.9 "safe metadata only"; never envelope, pair, uid, or plaintext).
     */
    @Provides @Singleton
    fun provideNudgeInboxRepository(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
        db: AccountabilityDatabase,
        keystore: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        uidResolver: PairUidResolver,
        partnerSelection: PartnerSelection,
        eventCipher: EventCipher,
        crashReporting: CrashReporting,
    ): NudgeInboxRepository = NudgeInboxRepository(
        enrollments = db.enrollmentDao()::all,
        selectedPairId = { partnerSelection.selectedPairId },
        ensureUid = { uidResolver.ensureUid() },
        deriveCek = { enrollment ->
            cekDerivation.deriveCek(
                enrollment.pairId,
                keystore.agreementPrivateKeyFor(enrollment.myAgreementKeyAlias),
                decodePublicKey(enrollment.partnerAgreementPubKey),
            )
        },
        listenNudges = { pairId, uid ->
            callbackFlow {
                val registration = FirestoreSchema.nudgeInboxQuery(firestore, pairId, uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        trySend(snapshot?.documents?.map(::nudgeDocOf).orEmpty())
                    }
                awaitClose { registration.remove() }
            }
        },
        ackBatch = { pairId, nudgeIds ->
            val batch = firestore.batch()
            nudgeIds.forEach { nudgeId ->
                batch.update(
                    FirestoreSchema.nudgeDocument(firestore, pairId, nudgeId),
                    FirestoreSchema.nudgeAckData(),
                )
            }
            batch.commit().await()
        },
        cancelNotifications = { PartnerNotifications.cancelPartnerNudge(context) },
        reportUnreadable = { cause -> crashReporting.nonFatal("partner_nudge_card", cause) },
        cipher = eventCipher,
    )

    /**
     * SOS §8.19 (Step 11): the recipient's block preference at
     * `pairings/{pairId}/nudgeSettings/{uid}` — the one document the Step-11 switch reads and
     * writes. A missing document reads as unblocked (the rules admit the read and the client
     * treats absence as the default); the write is a full `set` of exactly
     * [FirestoreSchema.nudgeSettingsData] — `blocked` + server-timestamp `updatedAt`, the only
     * keys R-F6 admits. Nothing else is touched: §8.19 forbids retroactive deletion of
     * delivered notifications, so enabling the block never cancels a tray entry and never
     * acks a pending nudge.
     */
    @Provides @Singleton
    fun provideNudgeSettingsRepository(
        firestore: FirebaseFirestore,
        db: AccountabilityDatabase,
        uidResolver: PairUidResolver,
        partnerSelection: PartnerSelection,
    ): NudgeSettingsRepository = NudgeSettingsRepository(
        enrollments = db.enrollmentDao()::all,
        selectedPairId = { partnerSelection.selectedPairId },
        ensureUid = { uidResolver.ensureUid() },
        readBlocked = { pairId, uid ->
            val snapshot = FirestoreSchema.nudgeSettingsDocument(firestore, pairId, uid).get().await()
            snapshot.getBoolean("blocked") == true
        },
        writeBlocked = { pairId, uid, blocked ->
            FirestoreSchema.nudgeSettingsDocument(firestore, pairId, uid)
                .set(FirestoreSchema.nudgeSettingsData(blocked))
                .await()
        },
    )

    /**
     * Plan §8 Step 7 (client) / AD-18a / §8.12: this device's push installation. `ensureUid`
     * is [PairUidResolver.ensureUid] itself — the one single-flighted anonymous sign-in (a
     * second concurrent sign-in path here is what minted duplicate anonymous accounts and
     * starved identity-pinned writes of their token, device-caught 2026-10-01); the token
     * fetch is FCM's cached getToken (a Task); the write is a full replace (no merge — a
     * server-set `stale` must clear, see [FirestoreSchema.pushInstallationData]). The
     * document id is the FID (§8.12, SOS Step 5): the same identity the push dispatcher
     * multicasts to and AD-18b acks key on. The legacy ANDROID_ID-keyed document (pre-SOS
     * releases) is deleted once, best-effort, after the first successful FID write — a
     * device reporting no FID (offline first run) registers nothing and keeps it (AD-05
     * sync fallback applies).
     */
    @Provides @Singleton
    fun providePushInstallationRegistrar(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
        messaging: FirebaseMessaging,
        resolver: PairUidResolver,
    ): PushInstallationRegistrar = PushInstallationRegistrar(
        ensureInstallationId = { PushInstallationRegistrar.installationIdOf() },
        platform = "android",
        appVersion = BuildConfig.VERSION_NAME,
        ensureUid = { resolver.ensureUid() },
        fetchToken = { messaging.token.await() },
        pushEligible = { PushInstallationRegistrar.pushEligibleOf(context) },
        write = { uid, installationId, data ->
            FirestoreSchema.pushInstallationDocument(firestore, uid, installationId).set(data).await()
        },
        legacyInstallationId = PushInstallationRegistrar.androidIdOf(context),
        deleteLegacy = { uid, installationId ->
            FirestoreSchema.pushInstallationDocument(firestore, uid, installationId).delete().await()
        },
    )

    /** Plan §7 item 9 / §6 steps 3+8 (AD-06b): the v2 drain writes to the partner's mailbox.
     *  `set()` is create-or-replace client-side, but the rules hold no update rule — a replay
     *  of an already-written message evaluates as an update and is denied, so the stored
     *  envelope is never re-written. */
    @Provides @Singleton
    fun provideMailboxOutboxDrainer(
        db: AccountabilityDatabase,
        firestore: FirebaseFirestore,
        crashReporting: CrashReporting,
    ): MailboxOutboxDrainer {
        val dao = db.outboxDao()
        return MailboxOutboxDrainer(
            pendingRows = dao::pendingForDrain,
            markSent = dao::markSent,
            recordAttempt = dao::recordAttempt,
            write = { partnerUid, messageId, data ->
                FirestoreSchema.mailboxMessageDocument(firestore, partnerUid, messageId)
                    .set(data).await()
            },
            onSendLatency = { millis ->
                crashReporting.nonFatal(
                    "${PartnerSyncTelemetry.EVENT_T1_MS} bucket=${PartnerSyncTelemetry.latencyBucket(millis)}",
                )
            },
        )
    }

    /**
     * §8 Step 18: reads the relay's rejection marker back off the sender's own mailbox
     * documents — the author-read the amended mailbox read clause grants (task #33 deploy
     * gate). A marker-less document and a cleaned-up one are both simply "no rejection".
     */
    @Provides @Singleton
    fun provideMailboxRejectionChecker(
        db: AccountabilityDatabase,
        firestore: FirebaseFirestore,
    ): MailboxRejectionChecker {
        val dao = db.outboxDao()
        return MailboxRejectionChecker(
            dueRows = dao::relayCheckDue,
            relayStatusOf = { partnerUid, messageId ->
                val snap = FirestoreSchema.mailboxMessageDocument(firestore, partnerUid, messageId).get().await()
                if (snap.exists()) snap.getString("relayStatus") else null
            },
            stampCheck = dao::stampRelayCheck,
        )
    }

    @Provides @Singleton
    fun provideIdentityEpochStore(db: AccountabilityDatabase): IdentityEpochStore =
        IdentityEpochStore(db.identityEpochDao())

    /** Step 13 (§8 Step 12, AD-07/AD-16): the disable-approval epoch-bound state machine. */
    @Provides @Singleton
    fun provideApprovalFlow(
        @ApplicationContext context: Context,
        dao: ApprovalRequestDao,
        enrollmentDao: EnrollmentDao,
        creator: EventCreator,
    ): ApprovalFlow = ApprovalFlow(
        dao = dao,
        enrollmentDao = enrollmentDao,
        creator = creator,
        // Copy names the other party (owner ruling 2026-09-29) — a supporter device can hold
        // several users' requests at once, each under its own per-pair notification id.
        notifyRequest = { enrollment ->
            PartnerNotifications.notifyApprovalRequest(context, enrollment.pairId, displayNameOf(enrollment))
        },
        notifyDecision = { enrollment ->
            PartnerNotifications.notifyApprovalDecision(context, enrollment.pairId, displayNameOf(enrollment))
        },
    )

    @Provides @Singleton
    fun provideEventCreator(
        store: ChainStateStore,
        enrollmentDao: EnrollmentDao,
        cekDerivation: CekDerivation,
        eventCipher: EventCipher,
        signer: AccountabilitySigner,
        epochStore: IdentityEpochStore,
        keystore: AccountabilityKeyStore,
        sessionKeyManager: SessionKeyManager,
    ): EventCreator = EventCreator(
        store = store,
        enrollmentDao = enrollmentDao,
        cekDerivation = cekDerivation,
        eventCipher = eventCipher,
        signer = signer,
        epochStore = epochStore,
        agreementPrivateKey = keystore::agreementPrivateKeyFor,
        sessionKeyManager = sessionKeyManager,
    )

    /** §8 Step 13 (AD-13a/b) + §10: the unpair protocol — journal, unconditional local shred,
     *  ack-based remote confirmation, rules-pinned revocation. */
    @Provides @Singleton
    fun provideBilateralDeletion(
        @ApplicationContext context: Context,
        db: AccountabilityDatabase,
        creator: EventCreator,
        drainer: MailboxOutboxDrainer,
        enrollmentDao: EnrollmentDao,
        store: ChainStateStore,
        keystore: AccountabilityKeyStore,
        exchange: PairingExchange,
        firestore: FirebaseFirestore,
    ): BilateralDeletion {
        return BilateralDeletion(
            creator = creator,
            drainOutbox = drainer::drain,
            enrollmentDao = enrollmentDao,
            store = store,
            journal = db.unpairStateDao(),
            destroyPairAgreementKey = keystore::destroyPairAgreementKey,
            destroyDeviceAgreementKey = keystore::destroyAgreementKey,
            terminatePair = exchange::terminatePair,
            deliverRequest = { partnerUid, row ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                val senderId = row.senderKeyId
                if (uid == null || senderId == null) {
                    false
                } else {
                    AppCheckInstaller.install(context)
                    try {
                        FirestoreSchema.mailboxMessageDocument(firestore, partnerUid, row.messageId).set(
                            FirestoreSchema.mailboxMessageData(
                                senderId = senderId,
                                senderUid = uid,
                                pairId = row.pairId,
                                payloadBase64 = Base64.getEncoder().encodeToString(row.requestEnvelope),
                                keyEpoch = row.keyEpoch,
                            ),
                        ).await()
                        true
                    } catch (e: FirebaseFirestoreException) {
                        // ALREADY_EXISTS: delivered by a crashed earlier attempt. Denied: either
                        // a rules rejection or that same replay (create-only rule) — see the
                        // drainer's ambiguity note; retrying is pointless either way and the
                        // remote-erasure claim never rests on this flag.
                        e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS ||
                            e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    } catch (e: Exception) {
                        false
                    }
                }
            },
            pollPartnerAck = { partnerUid, messageId ->
                AppCheckInstaller.install(context)
                FirestoreSchema.mailboxMessageDocument(firestore, partnerUid, messageId)
                    .collection(FirestoreSchema.ACKS_COLLECTION).limit(1).get().await()
                    .isEmpty.not()
            },
            ackReceived = { messageId ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                when {
                    uid == null -> false

                    else -> {
                        // FID at ack time (§8.12/AD-18b): the ack keys on the same
                        // installation identity the push dispatcher multicasts to.
                        val installationId = PushInstallationRegistrar.installationIdOf()
                        if (installationId == null) {
                            // No installation id: nothing this device can write; the AD-18b
                            // 30-day cap still bounds retention (same contract as the
                            // handler's ack seam).
                            true
                        } else {
                            AppCheckInstaller.install(context)
                            try {
                                FirestoreSchema.mailboxAckDocument(firestore, uid, messageId, installationId)
                                    .set(FirestoreSchema.mailboxAckData()).await()
                                true
                            } catch (e: FirebaseFirestoreException) {
                                // The acks rules hold no update rule: a retried ack is denied —
                                // "already acked" and "denied" are the same reading here, and this
                                // device is the only legitimate writer, so treat both as done.
                                e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS ||
                                    e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                            } catch (e: Exception) {
                                false
                            }
                        }
                    }
                }
            },
        )
    }

    @Provides @Singleton
    fun providePairingExchange(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
    ): PairingExchange = PairingExchange(context, firestore)

    /** AD-17b: region must equal the deployed function's region and the Firestore database region. */
    @Provides @Singleton
    fun provideFirebaseFunctions(): FirebaseFunctions = FirebaseFunctions.getInstance(PairingRegistration.REGION)

    @Provides @Singleton
    fun providePairingRegistration(
        @ApplicationContext context: Context,
        functions: FirebaseFunctions,
    ): PairingRegistration = PairingRegistration(context, functions)

    @Provides @Singleton
    fun provideEnrollmentFlow(
        signer: AccountabilitySigner,
        attestationVerifier: AttestationVerifier,
        cekDerivation: CekDerivation,
    ): EnrollmentFlow = EnrollmentFlow(signer, attestationVerifier, cekDerivation)

    @Provides @Singleton
    fun providePairingFlow(
        enrollmentFlow: EnrollmentFlow,
        keystore: AccountabilityKeyStore,
        enrollmentDao: EnrollmentDao,
        exchange: PairingExchange,
        registration: PairingRegistration,
        secretsRepository: SecretsRepository,
        storeFactory: AppRulesStoreFactory,
        reachability: PairingReachabilityStore,
    ): PairingFlow = PairingFlow(
        enrollmentFlow = enrollmentFlow,
        enrollmentDao = enrollmentDao,
        exchange = exchange,
        registration = registration,
        secretsRepository = secretsRepository,
        nowEpochMillis = System::currentTimeMillis,
        ensureIdentityKeys = { keystore.ensureKeys() },
        signingCertificateChain = keystore::signingCertificateChain,
        signingPublicKey = keystore::signingPublicKey,
        generateAgreementKey = keystore::generatePairAgreementKey,
        agreementPublicKey = keystore::pairAgreementPublicKey,
        agreementPrivateKey = keystore::pairAgreementPrivateKey,
        destroyAgreementKey = keystore::destroyPairAgreementKey,
        // Owner mandate 2026-10-04 (v16/ADR #135): a fresh pair owns no consent rows, so the
        // pair-complete snapshot it replaced is gone by construction — the catch-up snapshot
        // now fires from the sharing toggle that first makes a habit visible to the pair.
        onEnrolled = { pairId ->
            // OPD #30(b): AD-02 pair ids are deterministic, so re-pairing reuses the id — a
            // denial flag from the orphaned pairing would otherwise outlive the fix and put a
            // false re-pair card on a brand-new, working pairing.
            reachability.markReachable(pairId)
        },
    )

    @Provides @Singleton
    fun provideIdentityMigration(
        enrollmentDao: EnrollmentDao,
        epochStore: IdentityEpochStore,
        keystore: AccountabilityKeyStore,
    ): IdentityMigration = IdentityMigration(
        enrollmentDao = enrollmentDao,
        epochStore = epochStore,
    )

    @Provides @Singleton
    fun providePairUidResolver(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
        reachability: PairingReachabilityStore,
    ): PairUidResolver = PairUidResolver(context, firestore, reachability)

    /** Owner mandate 2026-10-04: the foreground realtime doorbell (see [PartnerLiveSync]). */
    @Provides @Singleton
    fun providePartnerLiveSync(
        firestore: FirebaseFirestore,
        enrollmentDao: EnrollmentDao,
        rollout: PartnerRolloutGate,
        resolver: PairUidResolver,
    ): PartnerLiveSync = PartnerLiveSync(
        firestore = firestore,
        enrollmentDao = enrollmentDao,
        rollout = rollout,
        ensureUid = resolver::ensureUid,
    )

    /** Plan §8 Step 10 / AD-08: requester-side cadence + escalation and the AD-08b responder. */
    @Provides @Singleton
    fun provideChainRepairCoordinator(
        enrollmentDao: EnrollmentDao,
        store: ChainStateStore,
        creator: EventCreator,
        signer: AccountabilitySigner,
    ): ChainRepairCoordinator = ChainRepairCoordinator(
        enrollmentDao = enrollmentDao,
        store = store,
        creator = creator,
        signer = signer,
    )

    /**
     * §7 item 12 (FR3): the dashboard's cache-first read model. Re-decoding a stored envelope
     * goes through the same production verify-then-decrypt pipeline the receive path uses
     * ([MailboxEventProcessor]), with CEK derivation mirroring [FirestoreMailboxReceiver]; a
     * row that no longer verifies decodes to null and is skipped, never presented.
     */
    @Provides @Singleton
    fun providePartnerDashboardSource(
        db: AccountabilityDatabase,
        store: ChainStateStore,
        storeFactory: AppRulesStoreFactory,
        keystore: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        processor: MailboxEventProcessor,
        reachability: PairingReachabilityStore,
    ): PartnerDashboardSource {
        val enrollmentDao = db.enrollmentDao()
        val eventDao = db.chainEventDao()
        val approvalDao = db.approvalRequestDao()
        return PartnerDashboardSource(
            enrollments = enrollmentDao::all,
            head = { pairId, senderKeyId -> store.localHead(pairId, senderKeyId) },
            receivedSince = eventDao::receivedSince,
            approvals = { pairId -> approvalDao.recentFor(pairId, 20) },
            unpairRows = db.unpairStateDao()::all,
            hasPriorPairingKeys = keystore::hasAgreementKey,
            // OPD #30(b): the denied-pairing flag the resolver records, read for the re-pair card.
            pairingDeniedAt = reachability::deniedAt,
            rejectionsSince = db.outboxDao()::rejectionCountSince,
            // §7 Step 11: a local Room read of the partner's shared aggregate — the dashboard
            // never waits on Firestore for a number the mailbox already delivered (plan §1).
            partnerActivity = { pairId ->
                storeFactory.open()?.partnerActivityDao()?.projection(pairId)?.let { row ->
                    PartnerActivityUi(
                        daysFree = row.daysFree,
                        milestonesReached = decodeMilestoneDays(row.milestonesReached),
                        relapseCount = row.relapseCount,
                        lastEventOccurredAtEpochMillis =
                            row.lastEventOccurredAtMs.takeIf { row.lastSequence > 0 },
                        pinnedMessageText = row.pinnedMessageText,
                        pinnedMessageAtEpochMillis = row.pinnedMessageAtMs,
                    )
                }
            },
            // Owner ask 2026-10-01: the sender's own latest message, read back from the
            // journal (ADR #115's table) — the Activity subpage shows the writer what they
            // last sent this partner.
            latestSentMessage = { pairId ->
                storeFactory.open()?.partnerActivityDao()
                    ?.latestSentMessageText(pairId, EventPayload.TYPE_MOTIVATIONAL_MESSAGE)
            },
            decoderFor = { enrollment ->
                val cek = cekDerivation.deriveCek(
                    enrollment.pairId,
                    keystore.agreementPrivateKeyFor(enrollment.myAgreementKeyAlias),
                    decodePublicKey(enrollment.partnerAgreementPubKey),
                )
                val partnerSigningKey = decodePublicKey(enrollment.partnerSigningPubKey)
                val decode: (ByteArray) -> EventPayload? = { envelope ->
                    // Step 15: stored rows may span epochs. This is a local re-read of the
                    // device's own history, not an ingress decision — resolve whatever epoch
                    // the envelope declares (the receive policy governs new traffic only).
                    val result = processor.process(
                        partnerSigningKey,
                        { epoch -> cekDerivation.deriveSessionKey(cek, epoch) },
                        envelope,
                    )
                    (result as? MailboxEventProcessor.EnvelopeResult.Valid)?.payload
                }
                decode
            },
        )
    }

    /** §14 (Step 19): the periodic pass reports notification-denied state and outbox age.
     *  Wired to the worker only — the dashboard's manual pass must not inflate the counts. */
    @Provides @Singleton
    fun providePartnerSyncTelemetry(
        @ApplicationContext context: Context,
        db: AccountabilityDatabase,
        crashReporting: CrashReporting,
    ): PartnerSyncTelemetry = PartnerSyncTelemetry(
        canPost = { NotificationAccess.canPost(context) },
        oldestPendingCreatedAt = db.outboxDao()::oldestPendingCreatedAt,
        report = { event -> crashReporting.nonFatal(event) },
    )

    @Provides @Singleton
    fun providePartnerSyncCoordinator(
        enrollmentDao: EnrollmentDao,
        storeFactory: AppRulesStoreFactory,
        creator: EventCreator,
        signer: AccountabilitySigner,
        drainer: MailboxOutboxDrainer,
        receiver: FirestoreMailboxReceiver,
        resolver: PairUidResolver,
        repair: ChainRepairCoordinator,
        approval: ApprovalFlow,
        deletion: BilateralDeletion,
        sessionKeyManager: SessionKeyManager,
        rejectionChecker: MailboxRejectionChecker,
        reachability: PairingReachabilityStore,
    ): PartnerSyncCoordinator = PartnerSyncCoordinator(
        enrollmentDao = enrollmentDao,
        storeFactory = storeFactory,
        creator = creator,
        signer = signer,
        resolveUids = resolver::resolve,
        drainOutbox = drainer::drain,
        pullMailbox = receiver::pull,
        repairPass = repair::repairPass,
        expireApprovals = approval::expireDue,
        resumeUnpairs = deletion::resumePending,
        rotateKeys = { pairId, senderKeyId ->
            sessionKeyManager.rotateIfDue(pairId, senderKeyId, System.currentTimeMillis())
        },
        checkRejections = { pairId, partnerUid -> rejectionChecker.check(partnerUid, pairId) },
        pruneReachability = reachability::prune,
    )

    /**
     * §7 item 10 receive pipeline: dedup (AD-10b), verify-then-decrypt, chain commit with the
     * processed marker in one transaction, AD-18b ack, generic notification (AD-11/NFR2).
     * The ack keys on the FID, fetched at ack time (§8.12) — the same installation identity
     * the push dispatcher multicasts to; a null FID (fetch failed) simply skips the ack and
     * the 30-day cap still bounds retention.
     */
    @Provides @Singleton
    fun providePartnerActivityReceiver(
        storeFactory: AppRulesStoreFactory,
    ): PartnerActivityReceiver = PartnerActivityReceiver(storeFactory)

    @Provides @Singleton
    fun provideMailboxMessageHandler(
        @ApplicationContext context: Context,
        db: AccountabilityDatabase,
        firestore: FirebaseFirestore,
        store: ChainStateStore,
        processor: MailboxEventProcessor,
        repair: ChainRepairCoordinator,
        approval: ApprovalFlow,
        deletion: BilateralDeletion,
        activityReceiver: PartnerActivityReceiver,
        enrollmentDao: EnrollmentDao,
    ): MailboxMessageHandler {
        val processedDao = db.processedEventDao()
        return MailboxMessageHandler(
            processed = processedDao::contains,
            process = processor::process,
            append = { pairId, messageId, signed, atEpochMillis ->
                store.receiveRemote(pairId, signed, messageId, atEpochMillis)
            },
            ack = { recipientUid, messageId ->
                PushInstallationRegistrar.installationIdOf()?.let { installationId ->
                    FirestoreSchema.mailboxAckDocument(firestore, recipientUid, messageId, installationId)
                        .set(FirestoreSchema.mailboxAckData()).await()
                }
            },
            // Step 13: approval messages own their notifications (ApprovalFlow posts them with
            // the right channel); the generic partner-event post skips those types. The other
            // party's label is read per post so the tray names which partner it concerns, and
            // the decoded payload rides along — a motivational message's own text, or a
            // wire-v2 relapse/milestone's streak name + time (owner mandate 2026-10-04).
            notify = { pairId, payload ->
                if (payload.type != EventPayload.TYPE_APPROVAL_REQUEST &&
                    payload.type != EventPayload.TYPE_APPROVAL_RESPONSE
                ) {
                    enrollmentDao.getByPairId(pairId)?.let { enrollment ->
                        PartnerNotifications.notifyPartnerEvent(
                            context, pairId, displayNameOf(enrollment), payload,
                        )
                    }
                }
            },
            respondToRequest = { pairId, sequence, hash ->
                repair.respondToRequest(pairId, sequence, hash)
            },
            // §8 Step 13: verify -> shred -> ack, inside BilateralDeletion (the ack is only
            // written once the erase completed — AD-13b's no-over-claim rule). #16: the
            // responder must learn the partnership ended — the label is read BEFORE the call,
            // because the shred destroys the enrollment row the name lives on. The post is
            // wrapped so a notification failure can never flip the shred result (that would
            // leave the request unacked and re-deliverable).
            onDeletionRequest = { pairId, messageId, atEpochMillis ->
                val name = runCatching { enrollmentDao.getByPairId(pairId)?.let(::displayNameOf) }.getOrNull()
                val shredded = deletion.handleIncoming(pairId, messageId)
                if (shredded && name != null) {
                    runCatching {
                        PartnerNotifications.notifyPartnershipEnded(context, pairId, name, atEpochMillis)
                    }.onFailure { Log.w(TAG, "partnership-ended notification failed pairId=$pairId", it) }
                }
                shredded
            },
            flagTamperIfRepairing = store::flagTamperIfRepairing,
            onAppended = { pairId, senderKeyId, sequence, payload, serverTs ->
                when (payload.type) {
                    EventPayload.TYPE_APPROVAL_REQUEST ->
                        approval.onRequestAppended(pairId, senderKeyId, sequence, payload, serverTs)
                    EventPayload.TYPE_APPROVAL_RESPONSE ->
                        approval.applyResponse(pairId, payload, serverTs)
                    // Non-activity types classify to NotActivity inside and are left untouched.
                    else -> activityReceiver.applyAppended(pairId, payload, sequence)
                }
            },
        )
    }

    @Provides @Singleton
    fun provideFirestoreMailboxReceiver(
        firestore: FirebaseFirestore,
        enrollmentDao: EnrollmentDao,
        store: ChainStateStore,
        keystore: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        sessionKeyManager: SessionKeyManager,
        handler: MailboxMessageHandler,
    ): FirestoreMailboxReceiver = FirestoreMailboxReceiver(
        firestore = firestore,
        enrollmentDao = enrollmentDao,
        store = store,
        keystore = keystore,
        cekDerivation = cekDerivation,
        sessionKeyManager = sessionKeyManager,
        handler = handler,
    )
}

/** §4.3 nudge document → the inbox seam's record. Every field is cast defensively: a
 *  wrong-typed or missing field degrades to null, and the repository treats the document as
 *  unreadable rather than trusting it. */
private fun nudgeDocOf(snapshot: DocumentSnapshot): NudgeInboxRepository.NudgeDoc =
    NudgeInboxRepository.NudgeDoc(
        nudgeId = snapshot.id,
        senderUid = snapshot.get("senderUid") as? String,
        envelope = snapshot.get("envelope") as? String,
        createdAtMs = (snapshot.get("createdAtMs") as? Number)?.toLong(),
        expiresAtMillis = snapshot.getTimestamp("expiresAt")?.toDate()?.time,
    )
