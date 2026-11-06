package com.example.myexodus.update

import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Plan §8.5: exact byte count and SHA-256 over the platform-provided stream, then archive
 * package/version/SDK checks. Signing continuity is left to Android's package manager;
 * client code deliberately performs no certificate-set equality.
 */
internal class ApkVerifier(private val context: Context) {
    fun verifyBytes(open: () -> InputStream?, release: UpdateRelease): Boolean {
        val input = open() ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > release.apkSizeBytes) return false
                digest.update(buffer, 0, read)
            }
        }
        return total == release.apkSizeBytes && digest.hex() == release.apkSha256Hex
    }

    fun verifyArchive(
        path: String?,
        release: UpdateRelease,
        installedVersionCode: Long,
        highestTrustedVersionCode: Long,
    ): AttemptError? {
        if (release.versionCode <= installedVersionCode) return AttemptError.RELEASE_INCOMPATIBLE
        if (release.versionCode < highestTrustedVersionCode) return AttemptError.RELEASE_INCOMPATIBLE
        if (release.minSdk > Build.VERSION.SDK_INT) return AttemptError.RELEASE_INCOMPATIBLE
        val file = path?.let(::File)
        if (file == null || !file.isFile) return AttemptError.APK_INTEGRITY_FAILURE
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return AttemptError.RELEASE_INCOMPATIBLE
        if (info.packageName != context.packageName) return AttemptError.RELEASE_INCOMPATIBLE
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (versionCode != release.versionCode) return AttemptError.RELEASE_INCOMPATIBLE
        val appInfo = info.applicationInfo ?: return AttemptError.RELEASE_INCOMPATIBLE
        if (appInfo.minSdkVersion != release.minSdk) return AttemptError.RELEASE_INCOMPATIBLE
        if (appInfo.targetSdkVersion != release.targetSdk) return AttemptError.RELEASE_INCOMPATIBLE
        return null
    }
}
