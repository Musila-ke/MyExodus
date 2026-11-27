package com.example.myexodus.update

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

internal sealed interface TransferOutcome {
    data object Complete : TransferOutcome
    data object Retry : TransferOutcome
    data class Terminal(val failure: AttemptError) : TransferOutcome
}

/**
 * ADR #321's transfer core: resumable HTTPS download of the signed artifact. Extracted from
 * [UpdateTransferWorker] unchanged so the resume/redirect/size-cap rules are testable without
 * a WorkManager harness (see UpdateTransferClientTest).
 */
internal class UpdateTransferClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 10,
    private val bufferBytes: Int = 64 * 1024,
    // https-only in production; the loopback test overrides to http for its local server.
    private val allowedProtocols: Set<String> = setOf("https"),
) {

    fun transfer(
        url: String,
        file: File,
        expectedBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): TransferOutcome {
        if (expectedBytes <= 0) return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
        var current = runCatching { URI(url).toURL() }.getOrNull()
            ?: return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
        var redirects = 0
        while (true) {
            if (current.protocol !in allowedProtocols) {
                return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
            }
            val connection = try {
                current.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return TransferOutcome.Retry
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "MyExodus-Updater")
                val existing = when {
                    !file.exists() -> 0L
                    file.length() > expectedBytes -> {
                        file.delete()
                        0L
                    }
                    else -> file.length()
                }
                if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        if (++redirects > maxRedirects) {
                            return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
                        }
                        val location = connection.getHeaderField("Location")
                            ?: return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
                        current = runCatching {
                            current.toURI().resolve(location).toURL()
                        }.getOrNull() ?: return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
                    }
                    code == HttpURLConnection.HTTP_PARTIAL ->
                        return copyBody(connection, file, existing, expectedBytes, onProgress)
                    code == HttpURLConnection.HTTP_OK ->
                        return copyBody(connection, file, 0L, expectedBytes, onProgress)
                    code == 416 -> {
                        file.delete()
                        return TransferOutcome.Retry
                    }
                    code == HttpURLConnection.HTTP_FORBIDDEN || code == 429 || code >= 500 ->
                        return TransferOutcome.Retry
                    else -> return TransferOutcome.Terminal(AttemptError.DOWNLOAD_FAILED)
                }
            } catch (e: SocketTimeoutException) {
                return TransferOutcome.Retry
            } catch (e: IOException) {
                return TransferOutcome.Retry
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun copyBody(
        connection: HttpURLConnection,
        file: File,
        offset: Long,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ): TransferOutcome = try {
        connection.inputStream.use { input ->
            FileOutputStream(file, offset > 0).use { raw ->
                val out = BufferedOutputStream(raw, bufferBytes)
                var total = offset
                val buffer = ByteArray(bufferBytes)
                while (total < expectedBytes) {
                    val room = minOf(buffer.size.toLong(), expectedBytes - total).toInt()
                    val read = input.read(buffer, 0, room)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
                out.flush()
                // The signer's byte count is authoritative; keep exactly that prefix.
                raw.fd.sync()
            }
        }
        if (file.length() == expectedBytes) TransferOutcome.Complete else TransferOutcome.Retry
    } catch (e: SocketTimeoutException) {
        TransferOutcome.Retry
    } catch (e: IOException) {
        if (e.message?.contains("ENOSPC", ignoreCase = true) == true) {
            TransferOutcome.Terminal(AttemptError.INSUFFICIENT_STORAGE)
        } else {
            TransferOutcome.Retry
        }
    }
}
