package com.example.myexodus.accountability.sync

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

/**
 * Server-published client compatibility (plan §8.10). A UX signal, never an authorization
 * control: rules and callables validate every operation independently. A missing document
 * or null minimum means no minimum has been published.
 */
data class ClientCompatibility(
    val minimumVersionCode: Long?,
    val updateMessage: String?,
) {
    fun blocks(versionCode: Int): Boolean =
        minimumVersionCode != null && versionCode.toLong() < minimumVersionCode
}

/**
 * Release 0 callable adapters (account plan §7 steps 2/4, §8.8, §8.10). Client seams that
 * must reach the server rather than Firestore directly:
 *
 * [appendUserEvent] replaces the direct mailbox create. The callable keeps the drainer's
 * failure contract: [MailboxOutboxDrainer] classifies `FirebaseFirestoreException` codes
 * (ALREADY_EXISTS = committed retry; PERMISSION_DENIED = leave pending), so the callable
 * error is translated into the same code space instead of changing the drainer.
 *
 * [initializeUserProfile] creates the ACTIVE profile every protected callable requires.
 *
 * [clientCompatibility] reads the public minimum-version document for the startup gate.
 */
object AccountCallables {

    suspend fun clientCompatibility(functions: FirebaseFunctions): ClientCompatibility {
        val reply = functions.getHttpsCallable("getClientCompatibility").call().await()
        @Suppress("UNCHECKED_CAST")
        val data = reply.data as? Map<String, Any?> ?: emptyMap()
        return ClientCompatibility(
            minimumVersionCode = (data["minimumVersionCode"] as? Number)?.toLong(),
            updateMessage = data["updateMessage"] as? String,
        )
    }

    suspend fun appendUserEvent(
        functions: FirebaseFunctions,
        eventId: String,
        data: Map<String, Any>,
    ) {
        try {
            functions.getHttpsCallable("appendUserEvent").call(
                mapOf(
                    "pairId" to data.getValue("pairId"),
                    "eventId" to eventId,
                    "senderId" to data.getValue("senderId"),
                    "keyEpoch" to data.getValue("keyEpoch"),
                    "ciphertextB64" to data.getValue("payload"),
                ),
            ).await()
        } catch (e: FirebaseFunctionsException) {
            throw FirebaseFirestoreException(
                e.message ?: "appendUserEvent failed",
                firestoreCodeOf(e.code),
            )
        }
    }

    suspend fun initializeUserProfile(functions: FirebaseFunctions) {
        functions.getHttpsCallable("initializeUserProfile").call().await()
    }

    internal fun firestoreCodeOf(code: FirebaseFunctionsException.Code): FirebaseFirestoreException.Code =
        when (code) {
            FirebaseFunctionsException.Code.ALREADY_EXISTS ->
                FirebaseFirestoreException.Code.ALREADY_EXISTS
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            -> FirebaseFirestoreException.Code.PERMISSION_DENIED
            FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                FirebaseFirestoreException.Code.FAILED_PRECONDITION
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                FirebaseFirestoreException.Code.INVALID_ARGUMENT
            else -> FirebaseFirestoreException.Code.UNAVAILABLE
        }
}
