package com.example.myexodus.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateFailure {
    OFFLINE,
    NO_RELEASE,
    SERVER,
    INVALID_MANIFEST,
    DOWNLOAD,
    INTEGRITY,
    INVALID_APK,
    WRONG_PACKAGE,
    VERSION_MISMATCH,
    DOWNGRADE,
    SIGNER_MISMATCH,
    INSTALL,
    PERMISSION,
    STORAGE,
}

internal sealed interface UpdateCheckResult {
    data class Available(val manifest: UpdateManifest, val envelopeJson: String) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NotConfigured : UpdateCheckResult
    data class Failure(val reason: UpdateFailure) : UpdateCheckResult
}

internal class UpdateException(val failure: UpdateFailure, cause: Throwable? = null) :
    Exception(cause)

internal class UpdateChecker(
    private val baseUrl: String = UpdateConfig.BASE_URL,
    private val publicKeyBase64: String = UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64,
) {
    fun check(installedVersionCode: Long): UpdateCheckResult {
        if (publicKeyBase64.isBlank()) return UpdateCheckResult.NotConfigured
        val envelope = try {
            fetchManifest()
        } catch (e: HttpStatusException) {
            val reason = if (e.code == HttpURLConnection.HTTP_NOT_FOUND) {
                UpdateFailure.NO_RELEASE
            } else {
                UpdateFailure.SERVER
            }
            return UpdateCheckResult.Failure(reason)
        } catch (e: ManifestTooLargeException) {
            return UpdateCheckResult.Failure(UpdateFailure.INVALID_MANIFEST)
        } catch (e: IOException) {
            return UpdateCheckResult.Failure(UpdateFailure.OFFLINE)
        }
        return when (val verified = verifyManifest(envelope, publicKeyBase64)) {
            is ManifestVerification.Valid ->
                if (verified.manifest.versionCode > installedVersionCode) {
                    UpdateCheckResult.Available(verified.manifest, envelope)
                } else {
                    UpdateCheckResult.UpToDate
                }
            ManifestVerification.NotConfigured -> UpdateCheckResult.NotConfigured
            is ManifestVerification.Invalid -> UpdateCheckResult.Failure(UpdateFailure.INVALID_MANIFEST)
        }
    }

    private fun fetchManifest(): String {
        val connection = URL(URL(baseUrl), UpdateConfig.MANIFEST_ASSET).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "MyExodus-Updater")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code)
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > UpdateConfig.MAX_MANIFEST_BYTES) throw ManifestTooLargeException()
                    out.write(buffer, 0, read)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
}

private class HttpStatusException(val code: Int) : IOException("HTTP $code")

private class ManifestTooLargeException : IOException("manifest too large")
