package com.example.myexodus.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Server answers the key-bundle flows branch on (account plan §9). */
enum class BackupKeyFailure {
    PERSISTENT_ACCOUNT_REQUIRED,
    EMAIL_VERIFICATION_REQUIRED,
    PROFILE_NOT_ACTIVE,
    ACCOUNT_DELETING,
    ALREADY_INITIALIZED,
    NOT_INITIALIZED,
    REVISION_CONFLICT,
    BUNDLE_IDENTITY_CHANGED,
    INVALID_BUNDLE,
    /** §8.5: the local high-water revision is newer than what the server just returned. */
    ROLLBACK,
    /** Wrong passphrase or recovery key — one generic result by design (§9). */
    WRONG_SECRET,
    PASS_TOO_SHORT,
    PASS_TOO_WEAK,
    SIGN_IN_REQUIRED,
    DENIED,
    NETWORK,
    UNKNOWN,
}

class BackupKeyException(
    val failure: BackupKeyFailure,
    /** Set on [BackupKeyFailure.REVISION_CONFLICT]: the revision the client must review. */
    val currentRevision: Long? = null,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/** The key-bundle reads and callables. Production is [FirestoreBackupKeyApi]. */
interface BackupKeyApi {
    suspend fun read(uid: String): RemoteBundle?
    suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle
    suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle
}

/**
 * §4.3: the client reads its own bundle directly (rules: owner read, active account only) and
 * every write goes through the two callables. Errors are a closed vocabulary so no server
 * message or stack ever reaches the UI.
 */
class FirestoreBackupKeyApi(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : BackupKeyApi {

    override suspend fun read(uid: String): RemoteBundle? = try {
        val snapshot = firestore.document("users/$uid/backupControl/keyBundle").get().await()
        if (!snapshot.exists()) {
            null
        } else {
            val data = snapshot.data ?: emptyMap()
            val bundle = BackupKeyBundle.fromWire(data)
                ?: throw BackupKeyException(BackupKeyFailure.INVALID_BUNDLE)
            val revision = BackupKeyBundle.revisionOf(data["revision"])
                ?: throw BackupKeyException(BackupKeyFailure.INVALID_BUNDLE)
            RemoteBundle(revision, bundle)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupKeyException) {
        throw e
    } catch (e: Exception) {
        throw BackupKeyException(failureOf(e), cause = e)
    }

    override suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle =
        call("initializeBackupKeyBundle", mapOf("bundle" to bundle.toWire()), bundle)

    override suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle =
        call(
            "updateBackupKeyBundle",
            mapOf("expectedRevision" to expectedRevision, "bundle" to bundle.toWire()),
            bundle,
        )

    private suspend fun call(
        name: String,
        data: Map<String, Any?>,
        sent: BackupKeyBundle,
    ): RemoteBundle = try {
        val response = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: throw BackupKeyException(BackupKeyFailure.UNKNOWN)
        // The callable echoes the stored bundle; fall back to what was sent if a field is
        // absent (the server response is a superset of the submitted body plus revision).
        val bundle = BackupKeyBundle.fromWire(toStringMap(response)) ?: sent
        val revision = BackupKeyBundle.revisionOf(response["revision"])
            ?: throw BackupKeyException(BackupKeyFailure.UNKNOWN)
        RemoteBundle(revision, bundle)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupKeyException) {
        throw e
    } catch (e: FirebaseFunctionsException) {
        throw BackupKeyException(
            failure = failureOf(e),
            currentRevision = currentRevisionOf(e),
            cause = e,
        )
    } catch (e: Exception) {
        throw BackupKeyException(failureOf(e), cause = e)
    }

    private fun failureOf(e: Exception): BackupKeyFailure = when (e) {
        is FirebaseFunctionsException -> when (e.message) {
            "PERSISTENT_ACCOUNT_REQUIRED" -> BackupKeyFailure.PERSISTENT_ACCOUNT_REQUIRED
            "EMAIL_VERIFICATION_REQUIRED" -> BackupKeyFailure.EMAIL_VERIFICATION_REQUIRED
            "PROFILE_NOT_ACTIVE" -> BackupKeyFailure.PROFILE_NOT_ACTIVE
            "ACCOUNT_DELETING" -> BackupKeyFailure.ACCOUNT_DELETING
            "ALREADY_INITIALIZED" -> BackupKeyFailure.ALREADY_INITIALIZED
            "NOT_INITIALIZED" -> BackupKeyFailure.NOT_INITIALIZED
            "REVISION_CONFLICT" -> BackupKeyFailure.REVISION_CONFLICT
            "BUNDLE_IDENTITY_CHANGED" -> BackupKeyFailure.BUNDLE_IDENTITY_CHANGED
            // The server validates the same fixed schema; a rejection means our own contract
            // is broken, never something the user can fix by retyping a secret.
            "INVALID_BUNDLE", "INVALID_INPUT", "INVALID_KEY_ID", "INVALID_PASSPHRASE_SALT",
            "INVALID_PASSPHRASE_WRAPPER", "INVALID_RECOVERY_KEY_ID", "INVALID_RECOVERY_SALT",
            "INVALID_RECOVERY_WRAPPER", "UNSUPPORTED_BUNDLE_VERSION",
            "UNSUPPORTED_KDF_PARAMETERS",
            -> BackupKeyFailure.INVALID_BUNDLE
            "EMAIL_NOT_VERIFIED" -> BackupKeyFailure.EMAIL_VERIFICATION_REQUIRED
            else -> when (e.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                -> BackupKeyFailure.NETWORK
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> BackupKeyFailure.SIGN_IN_REQUIRED
                FirebaseFunctionsException.Code.PERMISSION_DENIED -> BackupKeyFailure.DENIED
                FirebaseFunctionsException.Code.FAILED_PRECONDITION -> BackupKeyFailure.PROFILE_NOT_ACTIVE
                FirebaseFunctionsException.Code.ALREADY_EXISTS -> BackupKeyFailure.ALREADY_INITIALIZED
                else -> BackupKeyFailure.UNKNOWN
            }
        }
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> BackupKeyFailure.DENIED
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> BackupKeyFailure.SIGN_IN_REQUIRED
            FirebaseFirestoreException.Code.UNAVAILABLE -> BackupKeyFailure.NETWORK
            else -> BackupKeyFailure.UNKNOWN
        }
        else -> BackupKeyFailure.UNKNOWN
    }

    private fun currentRevisionOf(e: FirebaseFunctionsException): Long? {
        val details = e.details as? Map<*, *> ?: return null
        return BackupKeyBundle.revisionOf(details["currentRevision"])
    }

    private fun toStringMap(value: Map<*, *>): Map<String, Any?> =
        value.entries.associate { (k, v) -> k.toString() to v }
}

/**
 * Account plan §8.5: the highest bundle revision this device has seen is kept in protected
 * local state, so an unexplained rollback is rejected by default. A new device has no anchor
 * and cannot prove a malicious server did not replay an old bundle — that limit is documented,
 * not papered over.
 */
interface BackupRevisionStore {
    suspend fun highest(uid: String): Long?
    suspend fun record(uid: String, revision: Long)
}

class DataStoreBackupRevisionStore(
    private val dataStore: DataStore<Preferences>,
) : BackupRevisionStore {

    override suspend fun highest(uid: String): Long? = dataStore.data.first()[key(uid)]

    override suspend fun record(uid: String, revision: Long) {
        dataStore.edit { prefs ->
            val current = prefs[key(uid)] ?: 0L
            if (revision > current) prefs[key(uid)] = revision
        }
    }

    private fun key(uid: String) = longPreferencesKey("backup_bundle_revision_$uid")
}
