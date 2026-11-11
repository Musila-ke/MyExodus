package com.example.myexodus.accountability.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.google.firebase.auth.FirebaseAuth

/**
 * §4.1 BFU rule: the outbox and WorkManager both live in credential-encrypted storage, so
 * nothing is scheduled before the first OS unlock. BOOT_COMPLETED is already post-unlock on
 * FBE devices; the explicit check is the belt-and-braces the plan asks for. The immediate
 * pass also reconciles rows a crash left without scheduled work.
 */
class PartnerEventBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        if (FirebaseAuth.getInstance().currentUser == null) return
        enqueuePartnerEventSync(context)
        enqueuePartnerEventSyncNow(context)
    }
}
