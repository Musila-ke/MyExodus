package com.example.myexodus.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

internal sealed interface PolicyFetch {
    data class Verified(
        val envelopeJson: String,
        val policy: UpdatePolicy,
        val payloadHash: String,
    ) : PolicyFetch

    data class Failed(val failure: AttemptError) : PolicyFetch

    data object NotConfigured : PolicyFetch
}

internal sealed interface HttpResult {
    data class Body(val text: String) : HttpResult
    data class Failure(val failure: AttemptError) : HttpResult
}

internal fun interface PolicyHttpClient {
    fun fetch(url: String): HttpResult
}

/** Bounded HTTPS metadata fetch (plan §8.2): 10 s connect, 15 s read, 30 s overall. */
internal class HttpsPolicyClient : PolicyHttpClient {
    override fun fetch(url: String): HttpResult {
        // Long math is load-bearing: Int 30_000 * 1_000_000 overflows to -64_771_072 ns, which
        // puts the deadline in the past and fails every fetch as an instant NETWORK_TIMEOUT.
        val deadlineNanos = System.nanoTime() + UpdateConfig.OVERALL_TIMEOUT_MS.toLong() * 1_000_000
        var current = runCatching { URL(url) }.getOrNull()
            ?: return HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
        if (current.protocol != "https") {
            return HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
        }
        var redirects = 0
        while (true) {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return HttpResult.Failure(AttemptError.NETWORK_TIMEOUT)
            val connection = try {
                current.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return HttpResult.Failure(AttemptError.NETWORK_UNAVAILABLE)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout =
                    minOf(UpdateConfig.CONNECT_TIMEOUT_MS.toLong(), remainingMs).toInt()
                connection.readTimeout =
                    minOf(UpdateConfig.READ_TIMEOUT_MS.toLong(), remainingMs).toInt()
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "MyExodus-Updater")
                val code = connection.responseCode
                if (code in 300..399) {
                    if (++redirects > UpdateConfig.MAX_REDIRECTS) {
                        return HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
                    }
                    val location = connection.getHeaderField("Location")
                        ?: return HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
                    val next = runCatching { URL(current, location) }.getOrNull()
                        ?: return HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
                    if (next.protocol != "https" ||
                        !UpdateConfig.allowedRedirectHost(next.host) ||
                        next.userInfo != null
                    ) {
                        return HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
                    }
                    current = next
                    continue
                }
                return when {
                    code == HttpURLConnection.HTTP_NOT_FOUND ->
                        HttpResult.Failure(AttemptError.NO_STABLE_POLICY)
                    code == HttpURLConnection.HTTP_FORBIDDEN || code == 429 ->
                        HttpResult.Failure(AttemptError.HOST_THROTTLED)
                    code >= 500 -> HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
                    code != HttpURLConnection.HTTP_OK ->
                        HttpResult.Failure(AttemptError.HOST_UNAVAILABLE)
                    else -> readBody(connection, deadlineNanos)
                }
            } catch (e: SocketTimeoutException) {
                return HttpResult.Failure(AttemptError.NETWORK_TIMEOUT)
            } catch (e: IOException) {
                return HttpResult.Failure(AttemptError.NETWORK_UNAVAILABLE)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readBody(connection: HttpURLConnection, deadlineNanos: Long): HttpResult {
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    return HttpResult.Failure(AttemptError.NETWORK_TIMEOUT)
                }
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > UpdateConfig.MAX_ENVELOPE_BYTES) {
                    return HttpResult.Failure(AttemptError.POLICY_INVALID)
                }
                out.write(buffer, 0, read)
            }
            return HttpResult.Body(String(out.toByteArray(), Charsets.UTF_8))
        }
    }
}

internal class UpdateChecker(
    private val packageName: String,
    private val repository: String = UpdateConfig.releaseRepository,
    private val publicKeyBase64: String = UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64,
    private val client: PolicyHttpClient = HttpsPolicyClient(),
) {
    fun fetchPolicy(): PolicyFetch {
        if (repository.isBlank() || publicKeyBase64.isBlank()) return PolicyFetch.NotConfigured
        val url = "https://github.com/$repository/releases/latest/download/${UpdateConfig.MANIFEST_ASSET}"
        return when (val result = client.fetch(url)) {
            is HttpResult.Failure -> PolicyFetch.Failed(result.failure)
            is HttpResult.Body -> when (
                val verified = verifyPolicyEnvelope(
                    result.text,
                    publicKeyBase64,
                    packageName,
                    repository,
                )
            ) {
                is PolicyVerification.Valid ->
                    PolicyFetch.Verified(result.text, verified.policy, verified.payloadHash)
                PolicyVerification.NotConfigured -> PolicyFetch.NotConfigured
                is PolicyVerification.Invalid ->
                    PolicyFetch.Failed(AttemptError.POLICY_INVALID)
            }
        }
    }
}
