package com.example.myexodus.backup

import com.google.firebase.storage.FirebaseStorage
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The Storage read seam for restore (§6.6 item 4); production is [FirebaseObjectDownloader]. */
interface BackupObjectDownloader {
    /**
     * Downloads [objectPath] into [target]. Cancelling the caller cancels the task (§8.7);
     * the partial file is deleted by the caller's restore staging sweep.
     */
    suspend fun download(
        objectPath: String,
        target: File,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> },
    )
}

class FirebaseObjectDownloader(
    private val storage: FirebaseStorage,
) : BackupObjectDownloader {

    override suspend fun download(
        objectPath: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val task = storage.reference.child(objectPath).getFile(target)
        task.addOnProgressListener { snapshot ->
            if (snapshot.totalByteCount > 0) {
                onProgress(snapshot.bytesTransferred, snapshot.totalByteCount)
            }
        }
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { continuation.resume(Unit) }
            task.addOnFailureListener { continuation.resumeWithException(it) }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }
}
