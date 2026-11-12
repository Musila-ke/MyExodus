package com.example.myexodus.accountability.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.example.myexodus.accountability.sync.enqueuePartnerSyncNow

/**
 * §4.1 BFU rule: the outbox and WorkManager both live in credential-encrypted storage, so
 * nothing is scheduled before the first OS unlock. BOOT_COMPLETED is already post-unlock on
 * FBE devices; the explicit check is the belt-and-braces the plan asks for. The immediate
 * pass also reconciles rows a crash left without scheduled work.
 *
 * No FirebaseAuth guard: this receiver runs in a process the boot broadcast just created, and
 * `currentUser` can still be null while the persisted user restores — a race that silently
 * dropped every post-reboot resume (observed 2026-11-12: receivers delivered, zero work
 * enqueued). The workers fence themselves on the session, so enqueueing is safe signed-out.
 */
class PartnerEventBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        Log.i(TAG, "boot wake — resuming partner delivery")
        // A queued attempt lives only in the attempt log until a full pass stages it into the
        // outbox; the drain workers below cannot see it on their own (R09).
        enqueuePartnerSyncNow(context)
        enqueuePartnerEventSync(context)
        enqueuePartnerEventSyncNow(context)
        // §6.3: a permitted restart is a contact opportunity — re-arm the cadence and send now.
        enqueueDeviceContact(context)
        enqueueDeviceContactNow(context)
    }

    private companion object {
        const val TAG = "MyExodusPush"
    }
}
