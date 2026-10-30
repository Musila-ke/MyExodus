package com.example.myexodus.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Account plan §6.4/§6.5/§8.5: the client half of the key bundle. Generates the ABK and the
 * recovery key, wraps them, drives the two callables, records the high-water revision and — in
 * the only place that matters — proves a committed wrapper actually unlocks before the recovery
 * words are shown as final. Keys live in memory for one operation and are never persisted.
 */
class BackupKeyManager(
    private val api: BackupKeyApi,
    private val revisions: BackupRevisionStore,
    private val wordlists: BackupWordlists,
) {

    /** How the caller proves it may open the current ABK (§6.5 item 1). */
    sealed interface Unlock {
        data class Passphrase(val value: String) : Unlock
        data class RecoveryWords(val value: String) : Unlock
    }

    data class Created(val remote: RemoteBundle, val recoveryWords: List<String>)

    data class Rotated(val remote: RemoteBundle, val recoveryWords: List<String>)

    /** §8.5: a known device rejects an unexplained revision regression by default. */
    suspend fun load(uid: String): RemoteBundle? {
        val remote = api.read(uid) ?: return null
        val highest = revisions.highest(uid)
        if (highest != null && remote.revision < highest) {
            throw BackupKeyException(BackupKeyFailure.ROLLBACK)
        }
        revisions.record(uid, remote.revision)
        return remote
    }

    fun checkPassphrase(passphrase: String, generated: Boolean = false): PassphrasePolicy.Result =
        PassphrasePolicy.check(passphrase, generated)

    fun generatePassphrase(): String =
        wordlists.generatePassphrase().joinToString(SEPARATOR)

    suspend fun create(
        uid: String,
        passphrase: String,
        generated: Boolean = false,
    ): Created {
        requirePassphrase(passphrase, generated)
        val params = BackupCrypto.ARGON2
        val abk = BackupCrypto.random(BackupCrypto.ABK_BYTES)
        val keyId = BackupCrypto.random(BackupCrypto.KEY_ID_BYTES)
        val recoveryKey = BackupCrypto.random(BackupCrypto.ABK_BYTES)
        val recoveryKeyId = BackupCrypto.random(BackupCrypto.KEY_ID_BYTES)
        val salt = BackupCrypto.random(BackupCrypto.SALT_BYTES)
        val hkdfSalt = BackupCrypto.random(BackupCrypto.SALT_BYTES)
        val passNonce = BackupCrypto.random(BackupCrypto.NONCE_BYTES)
        val recoveryNonce = BackupCrypto.random(BackupCrypto.NONCE_BYTES)
        val passAad = BackupCrypto.passphraseAad(
            uid, keyId, BackupCrypto.BUNDLE_FORMAT_VERSION, params, salt,
        )
        val recoveryAad = BackupCrypto.recoveryAad(
            uid, keyId, recoveryKeyId, BackupCrypto.BUNDLE_FORMAT_VERSION, hkdfSalt,
        )
        val bundle = BackupKeyBundle(
            bundleFormatVersion = BackupCrypto.BUNDLE_FORMAT_VERSION,
            keyId = keyId,
            argon2 = params,
            passphraseSalt = salt,
            passphraseWrapper = WrappedKey(
                passNonce,
                BackupCrypto.wrap(
                    abk, BackupCrypto.derivePassphraseKek(passphrase, salt, params), passNonce, passAad,
                ),
            ),
            recoveryKeyId = recoveryKeyId,
            recoveryHkdfSalt = hkdfSalt,
            recoveryWrapper = WrappedKey(
                recoveryNonce,
                BackupCrypto.wrap(
                    abk,
                    BackupCrypto.deriveRecoveryKek(recoveryKey, hkdfSalt),
                    recoveryNonce,
                    recoveryAad,
                ),
            ),
        )
        dispatchGuard()
        val remote = api.initialize(bundle)
        val words = Bip39.encode(recoveryKey, wordlists.bip39)
        // §6.5: nothing is shown as final until the stored wrappers are proven to open the ABK.
        verifyWrappers(remote, abk, passphrase, deriveRecoveryKey(words), uid)
        revisions.record(uid, remote.revision)
        return Created(remote, words)
    }

    /** §6.5 item 1: the old passphrase may be replaced by unlocking with the recovery key. */
    suspend fun changePassphrase(
        uid: String,
        current: RemoteBundle,
        unlock: Unlock,
        newPassphrase: String,
        generated: Boolean = false,
    ): RemoteBundle {
        requirePassphrase(newPassphrase, generated)
        val abk = unlockAbk(uid, current, unlock)
        val bundle = current.bundle
        val salt = BackupCrypto.random(BackupCrypto.SALT_BYTES)
        val nonce = BackupCrypto.random(BackupCrypto.NONCE_BYTES)
        val kek = BackupCrypto.derivePassphraseKek(newPassphrase, salt, bundle.argon2)
        val aad = BackupCrypto.passphraseAad(
            uid, bundle.keyId, bundle.bundleFormatVersion, bundle.argon2, salt,
        )
        val next = bundle.copy(
            passphraseSalt = salt,
            passphraseWrapper = WrappedKey(nonce, BackupCrypto.wrap(abk, kek, nonce, aad)),
        )
        dispatchGuard()
        val remote = api.update(current.revision, next)
        val stored = remote.bundle
        verifyPassphraseWrapper(
            remote,
            abk,
            kek,
            stored.passphraseWrapper.nonce,
            BackupCrypto.passphraseAad(
                uid, stored.keyId, stored.bundleFormatVersion, stored.argon2, stored.passphraseSalt,
            ),
        )
        revisions.record(uid, remote.revision)
        return remote
    }

    /** §6.5 item 1: replacing a lost recovery key requires the current passphrase. */
    suspend fun rotateRecoveryKey(
        uid: String,
        current: RemoteBundle,
        currentPassphrase: String,
    ): Rotated {
        val abk = unlockAbk(uid, current, Unlock.Passphrase(currentPassphrase))
        val bundle = current.bundle
        val recoveryKey = BackupCrypto.random(BackupCrypto.ABK_BYTES)
        val recoveryKeyId = BackupCrypto.random(BackupCrypto.KEY_ID_BYTES)
        val hkdfSalt = BackupCrypto.random(BackupCrypto.SALT_BYTES)
        val nonce = BackupCrypto.random(BackupCrypto.NONCE_BYTES)
        val aad = BackupCrypto.recoveryAad(
            uid, bundle.keyId, recoveryKeyId, bundle.bundleFormatVersion, hkdfSalt,
        )
        val next = bundle.copy(
            recoveryKeyId = recoveryKeyId,
            recoveryHkdfSalt = hkdfSalt,
            recoveryWrapper = WrappedKey(
                nonce,
                BackupCrypto.wrap(
                    abk, BackupCrypto.deriveRecoveryKek(recoveryKey, hkdfSalt), nonce, aad,
                ),
            ),
        )
        dispatchGuard()
        val remote = api.update(current.revision, next)
        val words = Bip39.encode(recoveryKey, wordlists.bip39)
        // §6.5 item 2: the self-test runs through the words the user will actually type back.
        verifyWrappers(remote, abk, currentPassphrase, deriveRecoveryKey(words), uid)
        revisions.record(uid, remote.revision)
        return Rotated(remote, words)
    }

    /**
     * §6.4: the KDF is CPU-bound and does not suspend, so a cancel during it is only visible at
     * the next explicit check — and it must win before a mutating callable leaves the device.
     * `FirebaseFunctions.call()` dispatches the request before `await()` can observe
     * cancellation, so the check cannot sit below this seam.
     */
    private suspend fun dispatchGuard() {
        currentCoroutineContext().ensureActive()
    }

    private suspend fun unlockAbk(uid: String, remote: RemoteBundle, unlock: Unlock): ByteArray {
        val bundle = remote.bundle
        return when (unlock) {
            is Unlock.Passphrase -> {
                val kek = BackupCrypto.derivePassphraseKek(unlock.value, bundle.passphraseSalt, bundle.argon2)
                BackupCrypto.unwrap(
                    bundle.passphraseWrapper.ciphertext,
                    kek,
                    bundle.passphraseWrapper.nonce,
                    BackupCrypto.passphraseAad(
                        uid, bundle.keyId, bundle.bundleFormatVersion, bundle.argon2,
                        bundle.passphraseSalt,
                    ),
                )
            }
            is Unlock.RecoveryWords -> {
                val recoveryKey = Bip39.decode(Bip39.split(unlock.value), wordlists.bip39)
                if (recoveryKey != null) {
                    BackupCrypto.unwrap(
                        bundle.recoveryWrapper.ciphertext,
                        BackupCrypto.deriveRecoveryKek(recoveryKey, bundle.recoveryHkdfSalt),
                        bundle.recoveryWrapper.nonce,
                        BackupCrypto.recoveryAad(
                            uid, bundle.keyId, bundle.recoveryKeyId, bundle.bundleFormatVersion,
                            bundle.recoveryHkdfSalt,
                        ),
                    )
                } else {
                    null
                }
            }
        } ?: throw BackupKeyException(BackupKeyFailure.WRONG_SECRET)
    }

    private fun deriveRecoveryKey(words: List<String>): ByteArray =
        Bip39.decode(words, wordlists.bip39)
            ?: throw BackupKeyException(BackupKeyFailure.UNKNOWN)

    /**
     * §6.5 item 2: the committed bundle must unwrap to the same ABK with both secrets before the
     * operation reports success — a wrapper that does not open is not a completed rotation.
     */
    private fun verifyWrappers(
        remote: RemoteBundle,
        abk: ByteArray,
        passphrase: String,
        recoveryKey: ByteArray,
        uid: String,
    ) {
        val bundle = remote.bundle
        val kek = BackupCrypto.derivePassphraseKek(passphrase, bundle.passphraseSalt, bundle.argon2)
        verifyPassphraseWrapper(remote, abk, kek, bundle.passphraseWrapper.nonce, BackupCrypto.passphraseAad(
            uid, bundle.keyId, bundle.bundleFormatVersion, bundle.argon2, bundle.passphraseSalt,
        ))
        val reopened = BackupCrypto.unwrap(
            bundle.recoveryWrapper.ciphertext,
            BackupCrypto.deriveRecoveryKek(recoveryKey, bundle.recoveryHkdfSalt),
            bundle.recoveryWrapper.nonce,
            BackupCrypto.recoveryAad(
                uid, bundle.keyId, bundle.recoveryKeyId, bundle.bundleFormatVersion,
                bundle.recoveryHkdfSalt,
            ),
        )
        if (!BackupCrypto.sameSecret(reopened, abk)) throw BackupKeyException(BackupKeyFailure.UNKNOWN)
    }

    private fun verifyPassphraseWrapper(
        remote: RemoteBundle,
        abk: ByteArray,
        kek: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ) {
        val reopened = BackupCrypto.unwrap(remote.bundle.passphraseWrapper.ciphertext, kek, nonce, aad)
        if (!BackupCrypto.sameSecret(reopened, abk)) throw BackupKeyException(BackupKeyFailure.UNKNOWN)
    }

    private fun requirePassphrase(passphrase: String, generated: Boolean) {
        when (PassphrasePolicy.check(passphrase, generated)) {
            PassphrasePolicy.Result.Accepted -> Unit
            PassphrasePolicy.Result.TooShort ->
                throw BackupKeyException(BackupKeyFailure.PASS_TOO_SHORT)
            is PassphrasePolicy.Result.TooWeak ->
                throw BackupKeyException(BackupKeyFailure.PASS_TOO_WEAK)
        }
    }

    private companion object {
        /** §8.5: words are displayed and typed with a plain space separator. */
        const val SEPARATOR = " "
    }
}
