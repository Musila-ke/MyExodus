package com.example.myexodus.di

import android.content.Context
import androidx.room3.Room
import com.example.myexodus.accountability.BilateralDeletion
import com.example.myexodus.accountability.EventCreator
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
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainEventDao
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.sync.FirestoreMailboxReceiver
import com.example.myexodus.accountability.sync.FirestoreRelay
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.await
import com.example.myexodus.accountability.sync.MailboxEventProcessor
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.accountability.sync.PairUidResolver
import com.example.myexodus.accountability.sync.PartnerSyncCoordinator
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.secrets.SecretsRepository
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * §7.5 wiring for the Accountability-Partner Protocol. The sync engines are provided as
 * singletons; UI consumption lands with the V1 accountability screens. FirebaseFirestore
 * resolves lazily (first injector wins) so the graph stays buildable until the
 * google-services.json + Firebase init step lands (§18).
 */
@Module
@InstallIn(SingletonComponent::class)
object AccountabilityModule {

    @Provides @Singleton
    fun provideAccountabilityDatabase(@ApplicationContext context: Context): AccountabilityDatabase =
        Room.databaseBuilder(context, AccountabilityDatabase::class.java, AccountabilityDatabase.DB_NAME)
            .addMigrations(
                AccountabilityDatabase.MIGRATION_1_2,
                AccountabilityDatabase.MIGRATION_2_3,
                AccountabilityDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    fun provideChainEventDao(db: AccountabilityDatabase): ChainEventDao = db.chainEventDao()

    @Provides
    fun provideEnrollmentDao(db: AccountabilityDatabase): EnrollmentDao = db.enrollmentDao()

    @Provides @Singleton
    fun provideChainStateStore(db: AccountabilityDatabase): ChainStateStore = ChainStateStore(db)

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
    fun provideFirestoreRelay(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
        eventDao: ChainEventDao,
    ): FirestoreRelay = FirestoreRelay(context, firestore, eventDao)

    @Provides @Singleton
    fun provideIdentityEpochStore(db: AccountabilityDatabase): IdentityEpochStore =
        IdentityEpochStore(db.identityEpochDao())

    @Provides @Singleton
    fun provideEventCreator(
        store: ChainStateStore,
        enrollmentDao: EnrollmentDao,
        cekDerivation: CekDerivation,
        eventCipher: EventCipher,
        signer: AccountabilitySigner,
        epochStore: IdentityEpochStore,
        keystore: AccountabilityKeyStore,
    ): EventCreator = EventCreator(
        store = store,
        enrollmentDao = enrollmentDao,
        cekDerivation = cekDerivation,
        eventCipher = eventCipher,
        signer = signer,
        epochStore = epochStore,
        agreementPrivateKey = keystore::agreementPrivateKey,
    )

    @Provides @Singleton
    fun provideBilateralDeletion(
        creator: EventCreator,
        relay: FirestoreRelay,
        enrollmentDao: EnrollmentDao,
        store: ChainStateStore,
        keystore: AccountabilityKeyStore,
        exchange: PairingExchange,
        firestore: FirebaseFirestore,
    ): BilateralDeletion = BilateralDeletion(
        creator = creator,
        drainOutbox = relay::drainOutbox,
        enrollmentDao = enrollmentDao,
        store = store,
        destroyAgreementKey = keystore::destroyAgreementKey,
        deletePairState = exchange::deletePairState,
        writeDeletionTombstone = { pairId ->
            // PairingExchange owns the Firestore boundary for pair docs; the tombstone is
            // the same class of write (deterministic doc id, append-only per the rules).
            firestore.collection(FirestoreSchema.DELETED_PAIRS_COLLECTION).document(pairId)
                .set(FirestoreSchema.deletedPairData(pairId)).await()
        },
    )

    @Provides @Singleton
    fun providePairingExchange(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
    ): PairingExchange = PairingExchange(context, firestore)

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
        secretsRepository: SecretsRepository,
    ): PairingFlow = PairingFlow(
        enrollmentFlow = enrollmentFlow,
        enrollmentDao = enrollmentDao,
        exchange = exchange,
        secretsRepository = secretsRepository,
        nowEpochMillis = System::currentTimeMillis,
        signingCertificateChain = keystore::signingCertificateChain,
        signingPublicKey = keystore::signingPublicKey,
        agreementPublicKey = keystore::agreementPublicKey,
        agreementPrivateKey = keystore::agreementPrivateKey,
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
    ): PairUidResolver = PairUidResolver(context, firestore)

    @Provides @Singleton
    fun providePartnerSyncCoordinator(
        enrollmentDao: EnrollmentDao,
        storeFactory: AppRulesStoreFactory,
        creator: EventCreator,
        signer: AccountabilitySigner,
        relay: FirestoreRelay,
        receiver: FirestoreMailboxReceiver,
        resolver: PairUidResolver,
    ): PartnerSyncCoordinator = PartnerSyncCoordinator(
        enrollmentDao = enrollmentDao,
        storeFactory = storeFactory,
        creator = creator,
        signer = signer,
        resolveUids = resolver::resolve,
        drainOutbox = relay::drainOutbox,
        pullMailbox = receiver::pull,
        respondToBackfill = relay::respondToBackfillRequests,
    )

    @Provides @Singleton
    fun provideFirestoreMailboxReceiver(
        firestore: FirebaseFirestore,
        enrollmentDao: EnrollmentDao,
        store: ChainStateStore,
        keystore: AccountabilityKeyStore,
        cekDerivation: CekDerivation,
        eventCipher: EventCipher,
        processor: MailboxEventProcessor,
        deletion: BilateralDeletion,
    ): FirestoreMailboxReceiver = FirestoreMailboxReceiver(
        firestore = firestore,
        enrollmentDao = enrollmentDao,
        store = store,
        keystore = keystore,
        cekDerivation = cekDerivation,
        eventCipher = eventCipher,
        processor = processor,
        onDeletionRequest = deletion::handleIncoming,
    )
}
