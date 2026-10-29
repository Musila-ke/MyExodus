package com.example.myexodus.accountability.sync

import com.example.myexodus.telemetry.CrashReporting
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The single Task→suspend bridge for this package (was duplicated in four files). */
suspend fun <T> Task<T>.await(): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}

/** Non-fatal event for a rules rejection (pinned by test; rides the one-issue rail). */
internal const val FIRESTORE_DENIED_EVENT = "firestore_denied"

/**
 * Plan §13.1 item 6: a Firestore rule rejection leaves no server-side trace (nothing is
 * logged for a denied write), so the client is the only observer. Reports PERMISSION_DENIED
 * with the calling operation as cause, then rethrows — every caller keeps its own handling.
 * Transport failures are not denials and are deliberately not reported.
 */
suspend fun <T> Task<T>.awaitReportingDenial(crash: CrashReporting, op: String): T =
    try {
        await()
    } catch (e: FirebaseFirestoreException) {
        if (isRulesDenial(e)) crash.nonFatal(FIRESTORE_DENIED_EVENT, op)
        throw e
    }

internal fun isRulesDenial(e: FirebaseFirestoreException): Boolean =
    e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
