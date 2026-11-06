package com.example.myexodus.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Plan §8.6: package replacement wins over a lost installer callback, so reconcile the owned
 * attempt against the installed version. Only lightweight state queries run here; the existing
 * main-process health refresh and repair UI handle protection truthfulness.
 */
@AndroidEntryPoint
class UpdatePackageReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: UpdateCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                coordinator.reconcileAfterReplace()
                coordinator.syncProtectionMarker()
            } finally {
                result.finish()
            }
        }
    }
}
