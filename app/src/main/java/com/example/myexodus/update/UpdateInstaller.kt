package com.example.myexodus.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import com.example.myexodus.applock.startExpectedExternal
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal class UpdateInstaller(private val context: Context) {
    sealed interface StageResult {
        data object Committed : StageResult
        data class Failed(val failure: AttemptError) : StageResult
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startExpectedExternal(intent, "settings")
    }

    /**
     * Plan §8.5: stage through MODE_FULL_INSTALL while hashing the exact bytes written,
     * fsync the still-open session stream, close every write stream, then commit. The caller
     * persists [onSessionCreated] immediately so the attempt owns the session across death.
     */
    fun stageAndCommit(
        attempt: UpdateAttempt,
        open: () -> InputStream?,
        onSessionCreated: (Int) -> Unit,
    ): StageResult {
        if (!canInstall()) return StageResult.Failed(AttemptError.NEEDS_INSTALL_PERMISSION)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        params.setAppPackageName(context.packageName)
        params.setSize(attempt.apkSizeBytes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            runCatching { params.setOriginatingUri(attempt.apkUrl.toUri()) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return StageResult.Failed(AttemptError.INSTALL_FAILED)
        }
        onSessionCreated(sessionId)

        var session: PackageInstaller.Session? = null
        var committed = false
        try {
            val opened = installer.openSession(sessionId)
            session = opened
            val input = open() ?: return StageResult.Failed(AttemptError.APK_INTEGRITY_FAILURE)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            opened.openWrite("base.apk", 0, attempt.apkSizeBytes).use { output ->
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > attempt.apkSizeBytes) {
                            return StageResult.Failed(AttemptError.APK_INTEGRITY_FAILURE)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
                opened.fsync(output)
            }
            if (total != attempt.apkSizeBytes || digest.hex() != attempt.apkSha256Hex) {
                return StageResult.Failed(AttemptError.APK_INTEGRITY_FAILURE)
            }
            // Plan §8.5: recheck authorization and version immediately before commit.
            if (!canInstall()) return StageResult.Failed(AttemptError.NEEDS_INSTALL_PERMISSION)
            if (installedVersionCode(context) >= attempt.versionCode) {
                return StageResult.Failed(AttemptError.INSTALL_CONFLICT)
            }
            opened.commit(statusPendingIntent(attempt, sessionId).intentSender)
            committed = true
            return StageResult.Committed
        } catch (e: IOException) {
            return StageResult.Failed(
                if (e.message?.contains("ENOSPC", ignoreCase = true) == true) {
                    AttemptError.INSUFFICIENT_STORAGE
                } else {
                    AttemptError.INSTALL_FAILED
                },
            )
        } catch (e: Exception) {
            return StageResult.Failed(AttemptError.INSTALL_FAILED)
        } finally {
            if (!committed) runCatching { session?.abandon() }
            runCatching { session?.close() }
        }
    }

    fun abandonSession(sessionId: Int) {
        runCatching {
            context.packageManager.packageInstaller.openSession(sessionId).use { it.abandon() }
        }
    }

    fun sessionExists(sessionId: Int): Boolean = runCatching {
        context.packageManager.packageInstaller.getSessionInfo(sessionId)
        true
    }.getOrDefault(false)

    private fun statusPendingIntent(attempt: UpdateAttempt, sessionId: Int): PendingIntent {
        val callback = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .putExtra(UpdateInstallReceiver.EXTRA_ATTEMPT_ID, attempt.attemptId)
            .putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(
            context,
            attempt.attemptId.hashCode(),
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or statusPendingIntentFlags(),
        )
    }

    internal companion object {
        /** targetSdk 35+ rejects an immutable status PendingIntent at commit(); mutable is required. */
        fun statusPendingIntentFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
            if (sdkInt >= 31) PendingIntent.FLAG_MUTABLE else 0
    }
}
