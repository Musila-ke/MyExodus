package com.example.myexodus.backup

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The Storage seam; production is [FirebaseObjectUploader]. */
interface BackupObjectUploader {
    /**
     * Uploads [file] to [objectPath], continuing [sessionUri] when non-null
     * (`putFile(uri, metadata, existingUploadUri)` — Firebase resumable sessions last about a
     * week). [onSession] receives the issued session URI as soon as the service returns it.
     * Cancelling the caller cancels the upload task (§8.7).
     */
    suspend fun upload(
        objectPath: String,
        file: File,
        sessionUri: String?,
        onSession: (String) -> Unit,
        onProgress: (transferred: Long, total: Long) -> Unit,
    )
}

class FirebaseObjectUploader(
    private val storage: FirebaseStorage,
) : BackupObjectUploader {

    override suspend fun upload(
        objectPath: String,
        file: File,
        sessionUri: String?,
        onSession: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ) {
        // §8.6 Storage rules require exactly this content type and a create-only write to the
        // reserved path; the SDK never changes object bytes, so the envelope stays exact.
        val metadata = StorageMetadata.Builder().setContentType(CONTENT_TYPE).build()
        val task = storage.reference.child(objectPath)
            .putFile(Uri.fromFile(file), metadata, sessionUri?.let(Uri::parse))
        task.addOnProgressListener { snapshot ->
            snapshot.uploadSessionUri?.toString()?.let(onSession)
            onProgress(snapshot.bytesTransferred, snapshot.totalByteCount)
        }
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { continuation.resume(Unit) }
            task.addOnFailureListener { continuation.resumeWithException(it) }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }

    private companion object {
        const val CONTENT_TYPE = "application/octet-stream"
    }
}

/**
 * §8.6/§6.6: Storage failures map to one closed vocabulary for both directions. A missing
 * object means an expired upload reservation for a write and a missing file for a read, so
 * the caller names that one code; it is retryable either way.
 */
internal fun storageFailure(e: Throwable, notFound: BackupUploadFailure): BackupUploadFailure = when {
    e is BackupUploadException -> e.failure
    e is StorageException -> when (e.errorCode) {
        StorageException.ERROR_NOT_AUTHENTICATED -> BackupUploadFailure.SIGN_IN_REQUIRED
        StorageException.ERROR_NOT_AUTHORIZED -> BackupUploadFailure.DENIED
        StorageException.ERROR_OBJECT_NOT_FOUND -> notFound
        StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
        StorageException.ERROR_UNKNOWN,
        -> BackupUploadFailure.NETWORK
        else -> BackupUploadFailure.UNKNOWN
    }
    else -> BackupUploadFailure.UNKNOWN
}
