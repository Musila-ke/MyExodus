package com.example.myexodus.update

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpsPolicyClientTest {

    // Regression: Int arithmetic in the deadline (30_000 * 1_000_000) overflows to a negative
    // nanosecond count, so every fetch failed as NETWORK_TIMEOUT before touching the network.
    // A refused local connection proves the fetch reached the socket layer instead.
    @Test
    fun closedPortIsNetworkUnavailableNotTimeout() {
        val result = HttpsPolicyClient().fetch("https://127.0.0.1:1/update-manifest.json")

        assertEquals(HttpResult.Failure(AttemptError.NETWORK_UNAVAILABLE), result)
    }
}
