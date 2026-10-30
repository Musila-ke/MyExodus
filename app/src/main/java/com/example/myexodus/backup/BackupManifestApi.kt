package com.example.myexodus.backup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/** Server answers the upload/manifest flows branch on (account plan §8.6/§9). */
enum class BackupUploadFailure {
    PERSISTENT_ACCOUNT_REQUIRED,
    EMAIL_VERIFICATION_REQUIRED,
    PROFILE_NOT_ACTIVE,
    ACCOUNT_DELETING,
    FEATURE_DISABLED,

    /** §8.6: the cloud has a snapshot this device has never materialized. */
    BACKUP_BASE_REQUIRED,
    REMOTE_BACKUP_AHEAD,
    REMOTE_BACKUP_EQUIVOCATION,
    REMOTE_BACKUP_ROLLBACK,
    BACKUP_CONFLICT,
    BACKUP_INVALID_REQUEST,
    BACKUP_RESERVATION_LIMIT,
    BACKUP_RESERVATION_EXPIRED,
    BACKUP_OBJECT_MISSING,
    BACKUP_OBJECT_MISMATCH,

    /** No active local profile: never upload an empty snapshot over a real cloud backup. */
    NO_LOCAL_DATA,

    /** The passphrase/recovery key did not unwrap the ABK (§6.4 item 5). */
    WRONG_SECRET,

    /** §8.7: the identity changed while the operation was in flight; nothing more is sent. */
    SESSION_CHANGED,

    /** §6.4 items 7/14: staging could not be written, verified or cleaned up. */
    STAGING_FAILED,

    /** The per-UID anchor record or the active database could not be read. */
    LOCAL_STATE_UNAVAILABLE,

    /** §2.1 item 15 caps were exceeded; nothing left the device. */
    PAYLOAD_TOO_LARGE,
    SIGN_IN_REQUIRED,
    DENIED,
    NETWORK,
    UNKNOWN,
}

class BackupUploadException(
    val failure: BackupUploadFailure,
    /** The server pointer that explains a base/rollback answer, when one was observed. */
    val current: BackupPair? = null,
    /** Set on [BackupUploadFailure.BACKUP_RESERVATION_LIMIT]; milliseconds until retry. */
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

data class BeginBackupRequest(
    val backupId: String,
    val schemaVersion: Int,
    val compressedBytes: Long,
    val objectBytes: Long,
    val baseRevision: Long?,
    val baseSha256: String?,
    val replaceCurrent: Boolean = false,
    val replaceRevision: Long? = null,
    val replaceSha256: String? = null,
)

data class BeginBackupResult(
    val backupId: String,
    val objectPath: String,
    val expiresAtMs: Long,
    val replayed: Boolean,
)

data class ExtendReservationResult(val expiresAtMs: Long, val extended: Boolean)

data class CommitManifestResult(val revision: Long, val sha256: String, val replayed: Boolean)

/** The manifest callables and the owner-readable pointer (§8.6). Production is [FirestoreBackupManifestApi]. */
interface BackupManifestApi {
    /** The current server pointer, or null when the account has no committed snapshot. */
    suspend fun current(uid: String): BackupPair?

    suspend fun begin(request: BeginBackupRequest): BeginBackupResult

    suspend fun extend(backupId: String): ExtendReservationResult

    suspend fun commit(backupId: String, sha256: String, objectBytes: Long): CommitManifestResult
}

/**
 * §6.4 items 11-13: the client side of the reservation/commit protocol. Revisions and sizes
 * are sent as JSON integers — the Functions Android serializer wraps Kotlin `Long` in an
 * `Int64Value` object, which the server's exact-schema validation correctly rejects, so this
 * boundary deliberately narrows to `Int` (every legal value fits: revisions are counts and
 * sizes are capped at 21 MiB). Errors map to a closed vocabulary; no server string reaches UI.
 */
class FirestoreBackupManifestApi(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : BackupManifestApi {

    override suspend fun current(uid: String): BackupPair? = try {
        val snapshot = firestore.document("users/$uid/backupControl/current").get().await()
        if (!snapshot.exists()) {
            null
        } else {
            val revision = (snapshot.get("revision") as? Number)?.toLong()
            val sha256 = snapshot.get("sha256") as? String
            if (revision == null || sha256 == null || revision < 1 || !isSha256(sha256)) {
                throw BackupUploadException(BackupUploadFailure.UNKNOWN)
            }
            BackupPair(revision, sha256)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUploadException) {
        throw e
    } catch (e: Exception) {
        throw BackupUploadException(failureOf(e), cause = e)
    }

    override suspend fun begin(request: BeginBackupRequest): BeginBackupResult {
        val response = call(
            "beginBackup",
            mapOf(
                "backupId" to request.backupId,
                "schemaVersion" to request.schemaVersion,
                "compressedBytes" to wireInt(request.compressedBytes),
                "objectBytes" to wireInt(request.objectBytes),
                "baseRevision" to request.baseRevision?.let(::wireRevision),
                "baseSha256" to request.baseSha256,
                "replaceCurrent" to request.replaceCurrent,
                "replaceRevision" to request.replaceRevision?.let(::wireRevision),
                "replaceSha256" to request.replaceSha256,
            ),
        )
        val backupId = response["backupId"] as? String
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        val objectPath = response["objectPath"] as? String
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        val expiresAtMs = (response["expiresAtMs"] as? Number)?.toLong()
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        return BeginBackupResult(
            backupId = backupId,
            objectPath = objectPath,
            expiresAtMs = expiresAtMs,
            replayed = response["replayed"] == true,
        )
    }

    override suspend fun extend(backupId: String): ExtendReservationResult {
        val response = call("extendBackupReservation", mapOf("backupId" to backupId))
        val expiresAtMs = (response["expiresAtMs"] as? Number)?.toLong()
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        return ExtendReservationResult(expiresAtMs, response["extended"] == true)
    }

    override suspend fun commit(
        backupId: String,
        sha256: String,
        objectBytes: Long,
    ): CommitManifestResult {
        val response = call(
            "commitBackupManifest",
            mapOf(
                "backupId" to backupId,
                "sha256" to sha256,
                "objectBytes" to wireInt(objectBytes),
            ),
        )
        val revision = (response["revision"] as? Number)?.toLong()
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        val stored = response["sha256"] as? String
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        // §6.4 item 13: the local anchors advance only to what the server actually stored.
        if (stored != sha256) throw BackupUploadException(BackupUploadFailure.BACKUP_OBJECT_MISMATCH)
        return CommitManifestResult(revision, stored, response["replayed"] == true)
    }

    private suspend fun call(name: String, data: Map<String, Any?>): Map<*, *> = try {
        functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: throw BackupUploadException(BackupUploadFailure.UNKNOWN)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUploadException) {
        throw e
    } catch (e: FirebaseFunctionsException) {
        throw BackupUploadException(
            failure = failureOf(e),
            retryAfterMs = retryAfterOf(e),
            cause = e,
        )
    } catch (e: Exception) {
        throw BackupUploadException(failureOf(e), cause = e)
    }

    private fun failureOf(e: Exception): BackupUploadFailure = when (e) {
        is FirebaseFunctionsException -> when (e.message) {
            "PERSISTENT_ACCOUNT_REQUIRED" -> BackupUploadFailure.PERSISTENT_ACCOUNT_REQUIRED
            "EMAIL_VERIFICATION_REQUIRED" -> BackupUploadFailure.EMAIL_VERIFICATION_REQUIRED
            "PROFILE_NOT_ACTIVE" -> BackupUploadFailure.PROFILE_NOT_ACTIVE
            "ACCOUNT_DELETING" -> BackupUploadFailure.ACCOUNT_DELETING
            "FEATURE_DISABLED" -> BackupUploadFailure.FEATURE_DISABLED
            "BACKUP_BASE_REQUIRED" -> BackupUploadFailure.BACKUP_BASE_REQUIRED
            "REMOTE_BACKUP_AHEAD" -> BackupUploadFailure.REMOTE_BACKUP_AHEAD
            "REMOTE_BACKUP_EQUIVOCATION" -> BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION
            "REMOTE_BACKUP_ROLLBACK" -> BackupUploadFailure.REMOTE_BACKUP_ROLLBACK
            "BACKUP_CONFLICT" -> BackupUploadFailure.BACKUP_CONFLICT
            "BACKUP_RESERVATION_LIMIT" -> BackupUploadFailure.BACKUP_RESERVATION_LIMIT
            "BACKUP_RESERVATION_EXPIRED" -> BackupUploadFailure.BACKUP_RESERVATION_EXPIRED
            "BACKUP_OBJECT_MISSING" -> BackupUploadFailure.BACKUP_OBJECT_MISSING
            "BACKUP_OBJECT_MISMATCH" -> BackupUploadFailure.BACKUP_OBJECT_MISMATCH
            "INVALID_INPUT", "INVALID_BACKUP_ID", "INVALID_SCHEMA_VERSION", "INVALID_BASE",
            "INVALID_REPLACE", "INVALID_SHA256", "INVALID_OBJECT_SIZE", "BACKUP_TOO_LARGE",
            -> BackupUploadFailure.BACKUP_INVALID_REQUEST
            "EMAIL_NOT_VERIFIED" -> BackupUploadFailure.EMAIL_VERIFICATION_REQUIRED
            else -> when (e.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                FirebaseFunctionsException.Code.ABORTED,
                -> BackupUploadFailure.NETWORK
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> BackupUploadFailure.SIGN_IN_REQUIRED
                FirebaseFunctionsException.Code.PERMISSION_DENIED -> BackupUploadFailure.DENIED
                FirebaseFunctionsException.Code.FAILED_PRECONDITION -> BackupUploadFailure.PROFILE_NOT_ACTIVE
                else -> BackupUploadFailure.UNKNOWN
            }
        }
        is FirebaseFirestoreException -> when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> BackupUploadFailure.DENIED
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> BackupUploadFailure.SIGN_IN_REQUIRED
            FirebaseFirestoreException.Code.UNAVAILABLE -> BackupUploadFailure.NETWORK
            else -> BackupUploadFailure.UNKNOWN
        }
        else -> BackupUploadFailure.UNKNOWN
    }

    private fun retryAfterOf(e: FirebaseFunctionsException): Long? {
        val details = e.details as? Map<*, *> ?: return null
        return (details["retryAfterMs"] as? Number)?.toLong()
    }

    /**
     * Functions Android wraps `Long` values in a protobuf wrapper object; the callable's
     * exact-integer schema would reject that. All legal sizes/revisions fit an Int.
     */
    private fun wireInt(value: Long): Int {
        if (value < 0 || value > Int.MAX_VALUE) {
            throw BackupUploadException(BackupUploadFailure.BACKUP_INVALID_REQUEST)
        }
        return value.toInt()
    }

    private fun wireRevision(value: Long): Int = wireInt(value)

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
