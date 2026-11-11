package com.example.myexodus.di

import android.content.Context
import com.example.myexodus.BuildConfig
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.delivery.ExistingE2eeRecipientEncryptor
import com.example.myexodus.accountability.delivery.FirebasePartnerDeliveryTransport
import com.example.myexodus.accountability.delivery.KeystoreServicePayloadProtector
import com.example.myexodus.accountability.delivery.PartnerDeliveryClock
import com.example.myexodus.accountability.delivery.PartnerDeliveryCoordinator
import com.example.myexodus.accountability.delivery.PartnerDeliveryTransport
import com.example.myexodus.accountability.delivery.PartnerEventDrain
import com.example.myexodus.accountability.delivery.PartnerEventKeyStore
import com.example.myexodus.accountability.delivery.PartnerEventRecorder
import com.example.myexodus.accountability.delivery.PartnerEventRegistrar
import com.example.myexodus.accountability.delivery.ServiceEventOutbox
import com.example.myexodus.accountability.delivery.ServicePayloadProtector
import com.example.myexodus.accountability.delivery.SystemPartnerDeliveryClock
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.sync.await
import com.example.myexodus.accountability.sync.decodePublicKey
import com.google.firebase.auth.FirebaseAuth
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
    fun providePartnerDeliveryClock(): PartnerDeliveryClock = SystemPartnerDeliveryClock

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

    @Provides @Singleton
    fun providePartnerEventDrain(
        outbox: ServiceEventOutbox,
        keyStore: PartnerEventKeyStore,
        transport: PartnerDeliveryTransport,
        registrar: PartnerEventRegistrar,
        recorder: PartnerEventRecorder,
        clock: PartnerDeliveryClock,
    ): PartnerEventDrain = PartnerEventDrain(
        outbox = outbox,
        keyStore = keyStore,
        transport = transport,
        registrar = registrar,
        recorder = recorder,
        clock = clock,
        ownerUid = { FirebaseAuth.getInstance().currentUser?.uid },
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
