package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OPD #30: the one local record of "this pairing's document denied us". Anonymous uids are the
 * account (§6), so a sign-out, an app-data clear, or a reinstall mints a new uid that is a member
 * of no pairing document — every read of the pair is then denied, sync freezes silently at the
 * last watermark, and nothing says why or what to do. Written by [PairUidResolver] on a definitive
 * server denial, cleared by any successful read of the same pairing, pruned when the pairing is no
 * longer enrolled.
 *
 * Prefs, not Room: the fact is about a pairing whose database access is failing, and the shape
 * matches the other small state stores ([com.example.myexodus.blocking.ProtectionStatusStore],
 * [com.example.myexodus.blocking.BootReconciliationStore]). See [isDefinitiveDenial] for why an
 * offline pass can never write it.
 */
@Singleton
class PairingReachabilityStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: the Hilt graph is built at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED) — CE SharedPreferences throw until first unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Epoch millis of the first denial for [pairId]; null = no evidence the pairing is gone. */
    fun deniedAt(pairId: String): Long? = prefs.getLong(key(pairId), 0L).takeIf { it > 0L }

    /** First denial wins — the card reports "since" and a later failing pass must not move it. */
    fun markDenied(pairId: String, atMillis: Long) {
        if (deniedAt(pairId) == null) prefs.edit { putLong(key(pairId), atMillis) }
    }

    /** Any successful read of the pairing is the disproof: the flag clears and the card goes. */
    fun markReachable(pairId: String) {
        if (deniedAt(pairId) != null) prefs.edit { remove(key(pairId)) }
    }

    /** Drops flags for pairings this device no longer holds (an unpair that completed, a wipe). */
    fun prune(activePairIds: Set<String>) {
        val stale = prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in activePairIds }
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach { remove(it) } }
    }

    private fun key(pairId: String) = "$KEY_PREFIX$pairId"

    private companion object {
        const val PREFS_NAME = "pairing_reachability"
        const val KEY_PREFIX = "denied_at_"
    }
}

/**
 * Only a server-side PERMISSION_DENIED is evidence the pairing no longer accepts this device: the
 * rules gate the document on `request.auth.uid in members`, so the answer is about this uid, not
 * the network. Everything a missing connection can produce — UNAVAILABLE, DEADLINE_EXCEEDED, a
 * null code — must never raise the card. That is OPD #30's false-positive guard, and it is
 * structural rather than time-based: the SDK only reports PERMISSION_DENIED off a rules
 * evaluation, which requires a server response.
 */
internal fun isDefinitiveDenial(code: FirebaseFirestoreException.Code?): Boolean =
    code == FirebaseFirestoreException.Code.PERMISSION_DENIED

/**
 * The whole "record a denial" decision. A rules denial is about this uid — but App Check
 * enforcement surfaces as the same PERMISSION_DENIED, and that one is about the build, not the
 * pairing (re-pairing could not clear it), so it must not count. Both halves are required before
 * the card is earned; [canAttest] failing (no token mintable) answers "no" and the next pass
 * re-checks.
 */
internal fun shouldRecordDenial(cause: Throwable?, canAttest: Boolean): Boolean =
    cause is FirebaseFirestoreException && isDefinitiveDenial(cause.code) && canAttest

/**
 * The clear decision: only a server answer disproves a denial. A read served from cache — an
 * offline pass — is not an answer and must not blink the card off.
 */
internal fun shouldClearDenial(present: Boolean, fromCache: Boolean): Boolean =
    present && !fromCache
