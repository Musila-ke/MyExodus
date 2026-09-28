package com.example.myexodus.accountability

import android.content.Context
import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentFlow
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import com.example.myexodus.accountability.sync.ChainRepairCoordinator
import com.example.myexodus.accountability.sync.FirestoreMailboxReceiver
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.MailboxEventProcessor
import com.example.myexodus.accountability.sync.MailboxMessageHandler
import com.example.myexodus.accountability.sync.MailboxOutboxDrainer
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.accountability.sync.PairingRegistration
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.secrets.SecretsRepository
import com.example.myexodus.secrets.SecretsStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.security.PrivateKey
import java.security.Signature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * The virtual partner for the §12 device round trip: a SECOND anonymous identity (its own
 * [FirebaseApp] → its own uid, auth, Firestore, Functions) carrying [PartnerTestKeys]'
 * attested keys. Every protocol leg it drives is production code — [PairingFlow],
 * [ChainRepairCoordinator], [MailboxOutboxDrainer] — wired to the secondary app's transport
 * instances; the local stores are B's own (a separate Room DB file), and only the enrollment
 * DAO and secrets repository are fakes — the real ones belong to A and a shared file would
 * overwrite A's enrollment record and pairing-key secret.
 *
 * App Check (task #21 — enforcement is ON): the secondary app gets the same production
 * provider as the main app (PlayIntegrity), installed here at construction. On a device that
 * cannot attest (emulator) the instrumented test overrides it with the debug provider —
 * [com.example.myexodus.accountability.sync.AppCheckTestSupport] — using a registered debug
 * token passed via `am instrument -e firebaseAppCheckDebugSecret <token>`.
 */
class PartnerTestIdentity(
    private val context: Context,
    private val cekDerivation: CekDerivation,
    private val eventCipher: EventCipher,
    private val epochStore: IdentityEpochStore,
    /** Distinct for a second concurrent virtual partner (its own FirebaseApp, auth, uid and
     *  DB file); the defaults are the single-partner rig every other test uses. */
    private val appName: String = APP_NAME,
    private val dbName: String = PARTNER_DB_NAME,
) {

    /**
     * B's own chain DB (a separate file, wiped at construction — B's uid is fresh per run, so
     * nothing needs to survive). The real DB keys everything by (pairId, senderKeyId): on one
     * shared DB, B's local appends and A's view of B's chain are the SAME head row, so B's
     * own writes would silently advance A's head past the deliberate gap and every receive
     * would read as Duplicate.
     */
    val db: AccountabilityDatabase = run {
        context.deleteDatabase(dbName)
        Room.databaseBuilder(context.applicationContext, AccountabilityDatabase::class.java, dbName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
    val store: ChainStateStore = ChainStateStore(db)

    val app: FirebaseApp = (FirebaseApp.getApps(context).firstOrNull { it.name == appName }
        ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, appName))
        .also {
            FirebaseAppCheck.getInstance(it)
                .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    val auth: FirebaseAuth = FirebaseAuth.getInstance(app)
    val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(app)
    val functions: FirebaseFunctions = FirebaseFunctions.getInstance(app, PairingRegistration.REGION)

    val enrollmentDao = InMemoryEnrollmentDao()
    val secretsRepository: SecretsRepository = NoOpSecretsRepository()

    /** Signs with the virtual partner's attested key; warm-window-gated like the real one. */
    val signer: AccountabilitySigner = object : AccountabilitySigner() {
        override fun beginSign(): Signature =
            Signature.getInstance(SIGNATURE_ALGORITHM)
                .apply { initSign(PartnerTestKeys.signingPrivateKey()) }
    }

    /**
     * A FRESH anonymous uid every run: pairId is derived from the uid pair, so a new uid
     * means a new pairing — the pairing leg is exercised on every run and the chain
     * scenarios always start at sequence 0 (a persistent uid would hit PAIRING_ACTIVE on
     * the second run). The attested keys persist in the keystore across runs and stay
     * valid: the attestation challenge is read from the chain itself, not a per-session
     * nonce.
     */
    suspend fun signIn(): String {
        auth.signOut()
        PartnerTestKeys.ensureKeys()
        val uid = auth.signInAnonymously().await().user?.uid ?: error("partner sign-in returned no user")
        Log.i(TAG, "virtual partner signed in: $uid")
        return uid
    }

    fun pairingFlow(): PairingFlow = PairingFlow(
        enrollmentFlow = EnrollmentFlow(signer, AttestationVerifier(), cekDerivation),
        enrollmentDao = enrollmentDao,
        exchange = PairingExchange(context, firestore),
        registration = PairingRegistration(context, functions),
        secretsRepository = secretsRepository,
        nowEpochMillis = System::currentTimeMillis,
        // The virtual partner's keys live in PartnerTestKeys, not the device keystore; the
        // device-identity mint is a no-op here (nothing to provision for the harness).
        ensureIdentityKeys = { AccountabilityKeyStore.GenerationResult.Success(strongBox = false) },
        signingCertificateChain = { PartnerTestKeys.signingChain() },
        signingPublicKey = { PartnerTestKeys.signingPublicKey() },
        // §19.5 per-pair keys (ADR #106) land on the one attested test keypair regardless of
        // alias — the harness must not write per-attempt keys into the device keystore.
        generateAgreementKey = { },
        agreementPublicKey = { PartnerTestKeys.agreementPublicKey() },
        agreementPrivateKey = { PartnerTestKeys.agreementPrivateKey() },
        destroyAgreementKey = { },
    )

    /** B's CEK view: ECDH over the virtual partner's agreement key (the [AccountabilityKeyStore]
     *  seam, mirroring [signer]'s beginSign override). The alias-scoped lookup must resolve to
     *  the same test key — the harness's records carry real per-attempt aliases. */
    private val partnerKeystore: AccountabilityKeyStore = object : AccountabilityKeyStore() {
        override fun agreementPrivateKey(): PrivateKey = PartnerTestKeys.agreementPrivateKey()
        override fun agreementPrivateKeyFor(alias: String?): PrivateKey = PartnerTestKeys.agreementPrivateKey()
    }

    fun eventCreator(): EventCreator = EventCreator(
        store = store,
        enrollmentDao = enrollmentDao,
        cekDerivation = cekDerivation,
        eventCipher = eventCipher,
        signer = signer,
        epochStore = epochStore,
        agreementPrivateKey = { _ -> PartnerTestKeys.agreementPrivateKey() },
        sessionKeyManager = SessionKeyManager(store),
    )

    fun repairCoordinator(): ChainRepairCoordinator = ChainRepairCoordinator(
        enrollmentDao = enrollmentDao,
        store = store,
        creator = eventCreator(),
        signer = signer,
    )

    /** Step 13 (ADR #48) requester half, wired like the DI provider — B's own rows, B's chain. */
    fun approvalFlow(): ApprovalFlow = ApprovalFlow(
        dao = db.approvalRequestDao(),
        enrollmentDao = enrollmentDao,
        creator = eventCreator(),
    )

    /**
     * B's receive pipeline: the production [FirestoreMailboxReceiver] over B's own DB and the
     * secondary app — same handler ordering as the DI graph, with the approval hook the
     * requester half needs (a verified `approval_response` moves B's REQUESTED row).
     */
    fun receiver(): FirestoreMailboxReceiver = FirestoreMailboxReceiver(
        firestore = firestore,
        enrollmentDao = enrollmentDao,
        store = store,
        keystore = partnerKeystore,
        cekDerivation = cekDerivation,
        sessionKeyManager = SessionKeyManager(store),
        handler = MailboxMessageHandler(
            processed = db.processedEventDao()::contains,
            process = MailboxEventProcessor(signer, eventCipher)::process,
            append = { pairId, messageId, signed, at ->
                store.receiveRemote(pairId, signed, messageId, at)
            },
            ack = { recipientUid, messageId ->
                PushInstallationRegistrar.androidIdOf(context)?.let { installationId ->
                    FirestoreSchema.mailboxAckDocument(firestore, recipientUid, messageId, installationId)
                        .set(FirestoreSchema.mailboxAckData()).await()
                }
            },
            onAppended = { pairId, _, _, payload, serverTs ->
                if (payload.type == EventPayload.TYPE_APPROVAL_RESPONSE) {
                    approvalFlow().applyResponse(pairId, payload, serverTs)
                }
            },
        ),
    )

    /** Production drainer wired to B's own DB + the secondary app — the replay leg's transport. */
    fun outboxDrainer(): MailboxOutboxDrainer = MailboxOutboxDrainer(
        pendingRows = { pairId, limit -> db.outboxDao().pendingForDrain(pairId, limit) },
        markSent = { messageId -> db.outboxDao().markSent(messageId) },
        recordAttempt = { messageId, at -> db.outboxDao().recordAttempt(messageId, at) },
        write = { partnerUid, messageId, data ->
            firestore.collection(MAILBOXES).document(partnerUid).collection(MESSAGES)
                .document(messageId).set(data).await()
        },
    )

    suspend fun mailboxMessages(uid: String) =
        firestore.collection(MAILBOXES).document(uid).collection(MESSAGES).get().await().documents

    suspend fun writeToMailbox(recipientUid: String, messageId: String, data: Map<String, Any>) {
        firestore.collection(MAILBOXES).document(recipientUid).collection(MESSAGES)
            .document(messageId).set(data).await()
    }

    private companion object {
        const val APP_NAME = "partner-test"
        const val PARTNER_DB_NAME = "myexodus_partner_test.db"
        const val MAILBOXES = "mailboxes"
        const val MESSAGES = "messages"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val TAG = "MyExodusPartnerTest"
    }
}

/** B's enrollment state is per-test-run: the real DAO row belongs to the real identity. */
class InMemoryEnrollmentDao : EnrollmentDao {
    private val records = mutableMapOf<String, EnrollmentRecord>()

    override suspend fun upsert(record: EnrollmentRecord) {
        records[record.pairId] = record
    }

    override suspend fun getByPairId(pairId: String): EnrollmentRecord? = records[pairId]

    override suspend fun all(): List<EnrollmentRecord> = records.values.toList()

    override suspend fun delete(record: EnrollmentRecord) {
        records.remove(record.pairId)
    }

    override suspend fun updateMailboxWatermark(pairId: String, ts: Long) {
        records[pairId]?.let { records[pairId] = it.copy(mailboxWatermarkTsEpochMillis = ts) }
    }

    override suspend fun updatePartnerLabel(pairId: String, label: String?) {
        records[pairId]?.let { records[pairId] = it.copy(partnerLabel = label) }
    }
}

/** The pairing key secret is the real identity's; the virtual partner never persists one. */
class NoOpSecretsRepository : SecretsRepository {
    override val status: Flow<SecretsStatus> = flowOf(SecretsStatus.OK)
    override val pairingKey: Flow<ByteArray?> = flowOf(null)
    override val escapeCode: Flow<ByteArray?> = flowOf(null)
    override suspend fun storePairingKey(material: ByteArray) = Unit
    override suspend fun storeEscapeCode(code: ByteArray) = Unit
}
