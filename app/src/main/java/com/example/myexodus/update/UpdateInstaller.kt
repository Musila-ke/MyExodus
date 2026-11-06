package com.example.myexodus.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

internal class UpdateInstaller(private val context: Context) {
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(file: File) {
        if (!canInstall()) throw UpdateException(UpdateFailure.PERMISSION)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            throw UpdateException(UpdateFailure.INSTALL, e)
        }
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            val status = PendingIntent.getBroadcast(
                context,
                0,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or statusPendingIntentFlags(),
            )
            session.commit(status.intentSender)
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            throw if (e is UpdateException) e else UpdateException(UpdateFailure.INSTALL, e)
        } finally {
            runCatching { session?.close() }
        }
    }

    internal companion object {
        /** targetSdk 35+ rejects an immutable status PendingIntent at commit(); mutable is required. */
        fun statusPendingIntentFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
            if (sdkInt >= 31) PendingIntent.FLAG_MUTABLE else 0
    }
}
