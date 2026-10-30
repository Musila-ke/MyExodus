package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §6.5/§8.5: creation, both unlock paths, rotation rules and the committed-wrapper
 * self-test. Every test runs the real Argon2id at the pinned profile — the fakes replace only
 * the network, never the crypto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupKeyManagerTest {

    private lateinit var api: FakeApi
    private lateinit var revisions: FakeRevisions
    private lateinit var manager: BackupKeyManager

    private val uid = "uid-fixture"
    private val passphrase = "7Gx!q2-Lm9@Rv4#Tz8"
    private val secondPassphrase = "Kq4~Zm8^Rn2%Wd6!Ls"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        api = FakeApi()
        revisions = FakeRevisions()
        manager = BackupKeyManager(api, revisions, BackupWordlists(context))
    }

    @Test
    fun create_returnsWordsAndRecordsRevision() = runBlocking {
        val created = manager.create(uid, passphrase)
        assertEquals(24, created.recoveryWords.size)
        assertEquals(1L, created.remote.revision)
        assertEquals(1L, revisions.highest(uid))
        assertEquals(1, api.initializeCalls)
    }

    @Test
    fun create_requiresPolicyCompliantPassphraseBeforeAnyServerCall() = runBlocking {
        val short = assertFailure { manager.create(uid, "tooShort1!") }
        assertEquals(BackupKeyFailure.PASS_TOO_SHORT, short)
        val weak = assertFailure { manager.create(uid, "passwordpassword") }
        assertEquals(BackupKeyFailure.PASS_TOO_WEAK, weak)
        assertEquals(0, api.initializeCalls)
    }

    @Test
    fun create_acceptsGeneratedPassphrase() = runBlocking {
        val generated = manager.generatePassphrase()
        assertEquals(BackupWordlists.GENERATED_WORDS, generated.split(" ").size)
        val created = manager.create(uid, generated, generated = true)
        assertEquals(24, created.recoveryWords.size)
    }

    @Test
    fun create_selfTestFailureIsNotACreatedBundle() = runBlocking {
        api.initializeTransform = { bundle ->
            val corrupted = bundle.passphraseWrapper.ciphertext.copyOf()
                .also { it[0] = (it[0] + 1).toByte() }
            bundle.copy(passphraseWrapper = bundle.passphraseWrapper.copy(ciphertext = corrupted))
        }
        val failure = assertFailure { manager.create(uid, passphrase) }
        assertEquals(BackupKeyFailure.UNKNOWN, failure)
        assertNull(revisions.highest(uid))
    }

    @Test
    fun changePassphrase_withPassphrase_newWorksOldFails() = runBlocking {
        val created = manager.create(uid, passphrase)
        val updated = manager.changePassphrase(
            uid,
            created.remote,
            BackupKeyManager.Unlock.Passphrase(passphrase),
            secondPassphrase,
        )
        assertEquals(2L, updated.revision)

        val old = assertFailure {
            manager.changePassphrase(
                uid, updated, BackupKeyManager.Unlock.Passphrase(passphrase), secondPassphrase,
            )
        }
        assertEquals(BackupKeyFailure.WRONG_SECRET, old)

        val next = manager.changePassphrase(
            uid,
            updated,
            BackupKeyManager.Unlock.Passphrase(secondPassphrase),
            passphrase,
        )
        assertEquals(3L, next.revision)
    }

    @Test
    fun changePassphrase_withRecoveryWords() = runBlocking {
        val created = manager.create(uid, passphrase)
        val words = created.recoveryWords.joinToString(" ")
        val updated = manager.changePassphrase(
            uid,
            created.remote,
            BackupKeyManager.Unlock.RecoveryWords(words),
            secondPassphrase,
        )
        assertEquals(2L, updated.revision)
        // The recovery key itself did not change, so the new passphrase and the same words both
        // still open the bundle.
        val viaWords = manager.changePassphrase(
            uid, updated, BackupKeyManager.Unlock.RecoveryWords(words), passphrase,
        )
        manager.changePassphrase(
            uid, viaWords, BackupKeyManager.Unlock.Passphrase(passphrase), secondPassphrase,
        )
        assertEquals(4L, revisions.highest(uid))
    }

    @Test
    fun changePassphrase_wrongSecretNeverReachesTheServer() = runBlocking {
        val created = manager.create(uid, passphrase)
        val failure = assertFailure {
            manager.changePassphrase(
                uid,
                created.remote,
                BackupKeyManager.Unlock.Passphrase("WrongPass!234567"),
                secondPassphrase,
            )
        }
        assertEquals(BackupKeyFailure.WRONG_SECRET, failure)
        val badWords = created.recoveryWords.toMutableList().also { it[0] = "abandon" }
        assertEquals(
            BackupKeyFailure.WRONG_SECRET,
            assertFailure {
                manager.changePassphrase(
                    uid,
                    created.remote,
                    BackupKeyManager.Unlock.RecoveryWords(badWords.joinToString(" ")),
                    secondPassphrase,
                )
            },
        )
        assertEquals(0, api.updateCalls)
        assertEquals(1L, revisions.highest(uid))
    }

    @Test
    fun changePassphrase_isBoundToTheUid() = runBlocking {
        val created = manager.create(uid, passphrase)
        val failure = assertFailure {
            manager.changePassphrase(
                "uid-other",
                created.remote,
                BackupKeyManager.Unlock.Passphrase(passphrase),
                secondPassphrase,
            )
        }
        assertEquals(BackupKeyFailure.WRONG_SECRET, failure)
        assertEquals(0, api.updateCalls)
    }

    @Test
    fun changePassphrase_selfTestFailureIsNotASuccess() = runBlocking {
        val created = manager.create(uid, passphrase)
        api.updateTransform = { bundle ->
            bundle.copy(
                passphraseSalt = ByteArray(BackupCrypto.SALT_BYTES) { 0x42 },
            )
        }
        val failure = assertFailure {
            manager.changePassphrase(
                uid,
                created.remote,
                BackupKeyManager.Unlock.Passphrase(passphrase),
                secondPassphrase,
            )
        }
        assertEquals(BackupKeyFailure.UNKNOWN, failure)
        assertEquals(1, api.updateCalls)
        assertEquals(1L, revisions.highest(uid))
    }

    @Test
    fun rotateRecoveryKey_newWordsWorkAndOldWordsFail() = runBlocking {
        val created = manager.create(uid, passphrase)
        val rotated = manager.rotateRecoveryKey(uid, created.remote, passphrase)
        assertEquals(2L, rotated.remote.revision)
        assertEquals(24, rotated.recoveryWords.size)

        val oldWords = created.recoveryWords.joinToString(" ")
        assertEquals(
            BackupKeyFailure.WRONG_SECRET,
            assertFailure {
                manager.changePassphrase(
                    uid,
                    rotated.remote,
                    BackupKeyManager.Unlock.RecoveryWords(oldWords),
                    secondPassphrase,
                )
            },
        )

        // Recovery rotation leaves the passphrase wrapper intact.
        val viaPassphrase = manager.changePassphrase(
            uid,
            rotated.remote,
            BackupKeyManager.Unlock.Passphrase(passphrase),
            secondPassphrase,
        )
        assertEquals(3L, viaPassphrase.revision)

        val newWords = rotated.recoveryWords.joinToString(" ")
        val next = manager.changePassphrase(
            uid,
            viaPassphrase,
            BackupKeyManager.Unlock.RecoveryWords(newWords),
            passphrase,
        )
        assertEquals(4L, next.revision)
    }

    @Test
    fun rotateRecoveryKey_requiresTheCurrentPassphrase() = runBlocking {
        val created = manager.create(uid, passphrase)
        val failure = assertFailure {
            manager.rotateRecoveryKey(uid, created.remote, "WrongPass!234567")
        }
        assertEquals(BackupKeyFailure.WRONG_SECRET, failure)
        assertEquals(0, api.updateCalls)
    }

    @Test
    fun rotateRecoveryKey_casConflictPropagatesOnceAndDoesNotRecordIt() = runBlocking {
        val created = manager.create(uid, passphrase)
        api.updateFailure = BackupKeyException(BackupKeyFailure.REVISION_CONFLICT, 5L)
        try {
            manager.rotateRecoveryKey(uid, created.remote, passphrase)
            throw AssertionError("expected REVISION_CONFLICT")
        } catch (e: BackupKeyException) {
            assertEquals(BackupKeyFailure.REVISION_CONFLICT, e.failure)
            assertEquals(5L, e.currentRevision)
        }
        assertEquals(1, api.updateCalls)
        assertEquals(1L, revisions.highest(uid))
    }

    @Test
    fun rotateRecoveryKey_selfTestRunsThroughTheWordsThatAreDisplayed() = runBlocking {
        val created = manager.create(uid, passphrase)
        api.updateTransform = { bundle ->
            bundle.copy(recoveryWrapper = created.remote.bundle.recoveryWrapper)
        }
        val failure = assertFailure {
            manager.rotateRecoveryKey(uid, created.remote, passphrase)
        }
        assertEquals(BackupKeyFailure.UNKNOWN, failure)
        assertEquals(1L, revisions.highest(uid))
    }

    @Test
    fun load_rejectsUnexplainedRevisionRegression() = runBlocking {
        val created = manager.create(uid, passphrase)
        revisions.record(uid, 5)
        api.stored = RemoteBundle(4, created.remote.bundle)
        val failure = assertFailure { manager.load(uid) }
        assertEquals(BackupKeyFailure.ROLLBACK, failure)
    }

    @Test
    fun load_recordsEveryRevisionItSeesAndReturnsNullWhenUnsetUp() = runBlocking {
        assertNull(manager.load(uid))
        val created = manager.create(uid, passphrase)
        val loaded = manager.load(uid)
        assertNotNull(loaded)
        assertEquals(1L, loaded!!.revision)

        api.stored = RemoteBundle(6, created.remote.bundle)
        assertEquals(6L, manager.load(uid)!!.revision)
        assertEquals(6L, revisions.highest(uid))

        api.stored = RemoteBundle(5, created.remote.bundle)
        assertEquals(BackupKeyFailure.ROLLBACK, assertFailure { manager.load(uid) })
    }

    @Test
    fun checkPassphrase_exposesThePolicy() {
        assertEquals(PassphrasePolicy.Result.Accepted, manager.checkPassphrase(passphrase))
        assertTrue(manager.checkPassphrase("short") is PassphrasePolicy.Result.TooShort)
    }

    private suspend fun assertFailure(block: suspend () -> Unit): BackupKeyFailure = try {
        block()
        throw AssertionError("expected BackupKeyException")
    } catch (e: BackupKeyException) {
        e.failure
    }

    private class FakeApi : BackupKeyApi {
        var stored: RemoteBundle? = null
        var initializeCalls = 0
        var updateCalls = 0
        var initializeTransform: ((BackupKeyBundle) -> BackupKeyBundle)? = null
        var updateTransform: ((BackupKeyBundle) -> BackupKeyBundle)? = null
        var updateFailure: BackupKeyException? = null

        override suspend fun read(uid: String): RemoteBundle? = stored

        override suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle {
            initializeCalls++
            if (stored != null) throw BackupKeyException(BackupKeyFailure.ALREADY_INITIALIZED)
            val committed = initializeTransform?.invoke(bundle) ?: bundle
            return RemoteBundle(1, committed).also { stored = it }
        }

        override suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle {
            updateCalls++
            updateFailure?.let { throw it }
            val current = stored ?: throw BackupKeyException(BackupKeyFailure.NOT_INITIALIZED)
            if (current.revision != expectedRevision) {
                throw BackupKeyException(BackupKeyFailure.REVISION_CONFLICT, current.revision)
            }
            val committed = updateTransform?.invoke(bundle) ?: bundle
            return RemoteBundle(current.revision + 1, committed).also { stored = it }
        }
    }

    private class FakeRevisions : BackupRevisionStore {
        private val values = mutableMapOf<String, Long>()
        override suspend fun highest(uid: String): Long? = values[uid]
        override suspend fun record(uid: String, revision: Long) {
            values[uid] = maxOf(values[uid] ?: 0L, revision)
        }
    }
}
