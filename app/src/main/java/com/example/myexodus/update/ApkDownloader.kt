package com.example.myexodus.update

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal class ApkDownloader(private val updatesDir: File) {
    fun download(
        url: URL,
        manifest: UpdateManifest,
        onProgress: (downloaded: Long) -> Unit = {},
    ): File {
        if (!updatesDir.exists() && !updatesDir.mkdirs()) throw UpdateException(UpdateFailure.STORAGE)
        val available = runCatching { StatFs(updatesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        if (available < manifest.apkSizeBytes + MIN_HEADROOM_BYTES) {
            throw UpdateException(UpdateFailure.STORAGE)
        }
        val target = File(updatesDir, "${manifest.versionCode}.apk")
        if (target.exists() && target.length() == manifest.apkSizeBytes &&
            sha256Hex(target) == manifest.apkSha256
        ) {
            return target
        }
        val part = File(updatesDir, "${manifest.versionCode}.apk.part")
        part.delete()
        try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "MyExodus-Updater")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateException(UpdateFailure.DOWNLOAD)
                }
                if (connection.contentLengthLong > UpdateConfig.MAX_APK_BYTES) {
                    throw UpdateException(UpdateFailure.STORAGE)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > UpdateConfig.MAX_APK_BYTES) {
                                throw UpdateException(UpdateFailure.STORAGE)
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onProgress(total)
                        }
                        output.fd.sync()
                    }
                }
                if (total != manifest.apkSizeBytes || digest.hex() != manifest.apkSha256) {
                    throw UpdateException(UpdateFailure.INTEGRITY)
                }
            } finally {
                connection.disconnect()
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw UpdateException(UpdateFailure.STORAGE)
            return target
        } catch (e: UpdateException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            throw UpdateException(UpdateFailure.DOWNLOAD, e)
        }
    }

    fun sweep(now: Long) {
        val cutoff = now - UpdateConfig.STAGED_APK_TTL_MS
        updatesDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private companion object {
        const val MIN_HEADROOM_BYTES = 16L * 1024 * 1024
    }
}
