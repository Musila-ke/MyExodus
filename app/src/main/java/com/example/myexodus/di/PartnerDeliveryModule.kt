package com.example.myexodus.di

import android.content.Context
import com.example.myexodus.BuildConfig
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.delivery.DeviceContactClient
import com.example.myexodus.accountability.delivery.DeviceContactProtocol
import com.example.myexodus.accountability.delivery.EnforcementHealthSnapshot
import com.example.myexodus.accountability.delivery.ExistingE2eeRecipientEncryptor
import com.example.myexodus.accountability.delivery.FirebasePartnerDeliveryTransport
import com.example.myexodus.accountability.delivery.KeystoreServicePayloadProtector
import com.example.myexodus.accountability.delivery.PartnerDeliveryClock
import com.example.myexodus.accountability.delivery.PartnerDeliveryCoordinator
import com.example.myexodus.accountability.delivery.PartnerDeliveryTransport
import com.example.myexodus.accountability.delivery.PartnerEventDrain
import com.example.myexodus.accountability.delivery.PartnerEventKeyStore
import com.example.myexodus.accountability.delivery.PartnerEventProducer
import com.example.myexodus.accountability.delivery.PartnerEventRecorder
import com.example.myexodus.accountability.delivery.PartnerEventRegistrar
import com.example.myexodus.accountability.delivery.PartnerMailboxDecryptor
import com.example.myexodus.accountability.delivery.PartnerMailboxReceiver
import com.example.myexodus.accountability.delivery.PartnerObservationProjection
import com.example.myexodus.accountability.delivery.ServiceEventOutbox
import com.example.myexodus.accountability.delivery.ServicePayloadProtector
import com.example.myexodus.accountability.delivery.SystemPartnerDeliveryClock
import com.example.myexodus.accountability.activity.PartnerActivityReceiver
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.monitoring.MonitoringSnapshotReceiver
import com.example.myexodus.accountability.monitoring.PartnerPermissionLossReporter
import com.example.myexodus.accountability.plan.PartnerPlanReceiver
import com.example.myexodus.accountability.rulecontrol.PartnerRuleChangeProjection
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerRolloutGate
import com.example.myexodus.accountability.sync.PartnerSyncTelemetry
import com.example.myexodus.accountability.sync.await
import com.example.myexodus.accountability.sync.decodePublicKey
import com.example.myexodus.blocking.BlockerAccessibilityService
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.hasLiveWebsiteRule
import com.example.myexodus.data.db.entity.isExpiredAt
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.appLabel
import com.example.myexodus.vpn.DnsSinkholeVpnService
import com.example.myexodus.vpn.VpnEngineState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/** Partner delivery plan §7 Phase 4 wiring: the outbox, signer, registrar, drain and worker all
 *  resolve from here; Phase 3 left these classes unbound. */
@Module
@InstallIn(SingletonComponent::class)
object PartnerDeliveryModule {

    @Provides @Singleton
    fun provideServiceEventOutbox(db: AccountabilityDatabase): ServiceEventOutbox =
        ServiceEventOutbox(db)

    @Provides @Singleton
    fun provideServicePayloadProtector(): ServicePayloadProtector =
        KeystoreServicePayloadProtector()

    @Provides @Singleton
    fun provideExistingE2eeRecipientEncryptor(
        db: AccountabilityDatabase,
        keys: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        eventCipher: EventCipher,
    ): ExistingE2eeRecipientEncryptor = ExistingE2eeRecipientEncryptor(
        enrollments = db.enrollmentDao()::all,
        deriveCek = { enrollment ->
            cekDerivation.deriveCek(
                enrollment.pairId,
                keys.agreementPrivateKeyFor(enrollment.myAgreementKeyAlias),
                decodePublicKey(enrollment.partnerAgreementPubKey),
            )
        },
        cipher = eventCipher,
    )

    @Provides @Singleton
    fun providePartnerMailboxDecryptor(
        db: AccountabilityDatabase,
        keys: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        eventCipher: EventCipher,
    ): PartnerMailboxDecryptor = PartnerMailboxDecryptor(
        enrollmentFor = db.enrollmentDao()::getByPairId,
        deriveCek = { enrollment ->
            cekDerivation.deriveCek(
                enrollment.pairId,
                keys.agreementPrivateKeyFor(enrollment.myAgreementKeyAlias),
                decodePublicKey(enrollment.partnerAgreementPubKey),
            )
        },
        cipher = eventCipher,
    )

    /** §8.6 Phase 5 recipient half: the mailbox the server fans partner events into. §13.4
     *  Phase 7: delivered signed rule changes project into the same feed row the legacy chain
     *  path writes, so the dashboard reads the same regardless of transport. */
    @Provides @Singleton
    fun providePartnerMailboxReceiver(
        db: AccountabilityDatabase,
        outbox: ServiceEventOutbox,
        decryptor: PartnerMailboxDecryptor,
        firestore: FirebaseFirestore,
        projection: PartnerRuleChangeProjection,
        observation: PartnerObservationProjection,
    ): PartnerMailboxReceiver = PartnerMailboxReceiver(
        dao = db.partnerMailboxDao(),
        ownerUid = { FirebaseAuth.getInstance().currentUser?.uid },
        recipientInstallationId = { uid -> outbox.ensureInstallation(uid).installationId },
        queryMailbox = { uid, installationId, fromMs, limit ->
            FirestoreSchema.partnerMailboxDocsAfter(firestore, uid, installationId, fromMs, limit)
        },
        decrypt = decryptor::open,
        onDelivered = { document, plaintext ->
            projection.onDelivered(document, plaintext)
            observation.onDelivered(document, plaintext)
        },
    )

    /** §13.3 Phase 7 recipient half for every non-rule staged kind: activity/streak/plan/
     *  attempt content and monitoring snapshots become the same local state and notification
     *  the legacy chain callback produced. */
    @Provides @Singleton
    fun providePartnerObservationProjection(
        @ApplicationContext context: Context,
        db: AccountabilityDatabase,
        storeFactory: AppRulesStoreFactory,
        activityReceiver: PartnerActivityReceiver,
        planReceiver: PartnerPlanReceiver,
        monitoringReceiver: MonitoringSnapshotReceiver,
        permissionLoss: PartnerPermissionLossReporter,
    ): PartnerObservationProjection = PartnerObservationProjection(
        context = context,
        storeFactory = storeFactory,
        enrollmentDao = db.enrollmentDao(),
        activityReceiver = activityReceiver,
        planReceiver = planReceiver,
        monitoringReceiver = monitoringReceiver,
        permissionLoss = permissionLoss,
    )

    @Provides @Singleton
    fun providePartnerRuleChangeProjection(
        @ApplicationContext context: Context,
        db: AccountabilityDatabase,
        storeFactory: AppRulesStoreFactory,
    ): PartnerRuleChangeProjection = PartnerRuleChangeProjection(
        storeFactory = storeFactory,
        enrollmentDao = db.enrollmentDao(),
        notify = { pairId, partnerName, change, identity ->
            PartnerNotifications.notifyRuleChanged(context, pairId, partnerName, change, identity)
        },
    )

    @Provides @Singleton
    fun providePartnerDeliveryClock(): PartnerDeliveryClock = SystemPartnerDeliveryClock

    /** §6.3 signed contact. The required mask is the effective policy — a capability a live
     *  rule actually needs, so an optional VPN is not reported as required. An unreadable
     *  rules table yields a null snapshot and the contact is skipped: absence of a report
     *  reads as unavailable, never as a fabricated healthy or fully-revoked claim. */
    @Provides @Singleton
    fun provideDeviceContactClient(
        @ApplicationContext context: Context,
        outbox: ServiceEventOutbox,
        keyStore: PartnerEventKeyStore,
        clock: PartnerDeliveryClock,
        storeFactory: AppRulesStoreFactory,
        functions: FirebaseFunctions,
    ): DeviceContactClient = DeviceContactClient(
        outbox = outbox,
        keyStore = keyStore,
        clock = clock,
        ownerUid = { FirebaseAuth.getInstance().currentUser?.uid },
        healthSnapshot = snapshot@{
            runCatching {
                val store = storeFactory.open() ?: return@snapshot null
                val nowMs = clock.wallClockMs()
                val rules = store.restrictionRuleDao().getActiveRules()
                EnforcementHealthSnapshot(
                    accessibilityEnabled = BlockerAccessibilityService.connected.get(),
                    vpnActive = DnsSinkholeVpnService.state == VpnEngineState.ACTIVE,
                    // No device-admin surface is implemented in this build (§7 Phase 6).
                    deviceAdminActive = false,
                    notificationPermissionGranted = NotificationAccess.canPost(context),
                    requiredCapabilities = buildList {
                        if (rules.any { it.isEnabled && !it.isExpiredAt(nowMs) }) {
                            add(DeviceContactProtocol.CAPABILITY_ACCESSIBILITY)
                        }
                        if (hasLiveWebsiteRule(rules, nowMs)) {
                            add(DeviceContactProtocol.CAPABILITY_VPN)
                        }
                    },
                )
            }.getOrNull()
        },
        send = { wire ->
            AppCheckInstaller.install(context)
            functions.getHttpsCallable("submitDeviceContact").call(wire).await().data
        },
    )

    @Provides @Singleton
    fun providePartnerDeliveryTransport(
        @ApplicationContext context: Context,
        functions: FirebaseFunctions,
    ): PartnerDeliveryTransport = FirebasePartnerDeliveryTransport(context, functions)

    @Provides @Singleton
    fun providePartnerEventRegistrar(
        @ApplicationContext context: Context,
        functions: FirebaseFunctions,
        keyStore: PartnerEventKeyStore,
        outbox: ServiceEventOutbox,
        db: AccountabilityDatabase,
        keys: AccountabilityKeyStore,
    ): PartnerEventRegistrar = PartnerEventRegistrar(
        appContext = context,
        functions = functions,
        keyStore = keyStore,
        outbox = outbox,
        recipientPublicKeySpki = {
            // One recipient key per installation on the server. The newest enrollment's
            // pair-scoped agreement key is the one partners encrypt to; a pre-v12 enrollment
            // falls back to the legacy device-level key (multi-pair limitation: one
            // registration can authorize only one of several pair keys at a time).
            val alias = runCatching { db.enrollmentDao().all() }
                .getOrDefault(emptyList())
                .firstNotNullOfOrNull { it.myAgreementKeyAlias }
            runCatching {
                if (alias != null) keys.pairAgreementPublicKey(alias).encoded
                else keys.agreementPublicKey().encoded
            }.getOrNull()
        },
        currentAuth = currentAuth@{
            val user = FirebaseAuth.getInstance().currentUser ?: return@currentAuth null
            // §8.3.1's five-minute window. Anonymous sessions have no reauthentication
            // concept; both physical test devices run anonymous accounts (documented).
            val fresh = user.isAnonymous || runCatching {
                val token = user.getIdToken(false).await()
                System.currentTimeMillis() / 1000 - token.authTimestamp <= 300
            }.getOrDefault(false)
            PartnerEventRegistrar.PartnerAuth(user.uid, fresh)
        },
        // Emulators carry no hardware attestation; a release build without a chain is a stop.
        allowUnattested = BuildConfig.DEBUG,
    )

    @Provides @Singleton
    fun providePartnerEventRecorder(
        outbox: ServiceEventOutbox,
        protector: ServicePayloadProtector,
        encryptor: ExistingE2eeRecipientEncryptor,
        clock: PartnerDeliveryClock,
        coordinator: Provider<PartnerDeliveryCoordinator>,
    ): PartnerEventRecorder = PartnerEventRecorder(
        outbox = outbox,
        protector = protector,
        encryptor = encryptor,
        clock = clock,
        ownerUid = { FirebaseAuth.getInstance().currentUser?.uid },
        onRecorded = { coordinator.get().onEventCommitted() },
    )

    /** §13.3 Phase 7 staged switch, now for every partner-visible queue: while the Remote
     *  Config flag is explicitly on, each queue row is sealed by the auth-free service key so
     *  delivery never raises a biometric prompt (owner demand 2026-11-12). The shipped legacy
     *  chain stays the default path until then. */
    @Provides @Singleton
    fun providePartnerEventProducer(
        @ApplicationContext context: Context,
        outbox: ServiceEventOutbox,
        recorder: PartnerEventRecorder,
        storeFactory: AppRulesStoreFactory,
        gate: PartnerRolloutGate,
    ): PartnerEventProducer = PartnerEventProducer(
        outbox = outbox,
        recorder = recorder,
        storeFactory = storeFactory,
        staged = { gate.ruleProducerStagedEnabled() },
        resolveRuleLabel = { _, target -> appLabel(context, target) },
    )

    @Provides @Singleton
    fun providePartnerEventDrain(
        outbox: ServiceEventOutbox,
        keyStore: PartnerEventKeyStore,
        transport: PartnerDeliveryTransport,
        registrar: PartnerEventRegistrar,
        recorder: PartnerEventRecorder,
        clock: PartnerDeliveryClock,
        gate: PartnerRolloutGate,
        crashReporting: CrashReporting,
    ): PartnerEventDrain = PartnerEventDrain(
        outbox = outbox,
        keyStore = keyStore,
        transport = transport,
        registrar = registrar,
        recorder = recorder,
        clock = clock,
        ownerUid = { FirebaseAuth.getInstance().currentUser?.uid },
        syncEnabled = { gate.clientSyncEnabled() },
        onRecipientKeyRegenerated = {
            crashReporting.nonFatal(PartnerSyncTelemetry.EVENT_RECIPIENT_KEY_REGENERATED)
        },
    )

    @Provides @Singleton
    fun providePartnerDeliveryCoordinator(
        @ApplicationContext context: Context,
        outbox: ServiceEventOutbox,
        drain: PartnerEventDrain,
    ): PartnerDeliveryCoordinator = PartnerDeliveryCoordinator(
        context = context,
        outbox = outbox,
        drain = drain,
        ownerUid = { FirebaseAuth.getInstance().currentUser?.uid },
    )
}
