package com.example.myexodus.accountability.delivery

import android.content.Context
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.await
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException

/**
 * §8.4 production transport: one `ingestPartnerEvents` callable round trip per batch.
 * Auth and App Check ride the callable protocol; a whole-request failure throws and the
 * drain classifies it, keeping the exact signed bytes (§8.2 immutable after possible
 * acceptance).
 */
class FirebasePartnerDeliveryTransport(
    private val appContext: Context,
    private val functions: FirebaseFunctions,
) : PartnerDeliveryTransport {

    override suspend fun submit(
        installationId: String,
        batch: PartnerEventBatchV1,
    ): PartnerEventBatchResultV1 {
        AppCheckInstaller.install(appContext)
        val data = try {
            functions.getHttpsCallable("ingestPartnerEvents")
                .call(
                    mapOf(
                        "installationId" to installationId,
                        "events" to batch.events.map { it.toWireMap() },
                    ),
                )
                .await()
                .data
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            // §8.4/§9: a whole-request quota rejection retries on a longer fixed defer —
            // callables carry no Retry-After for the client to honor.
            if (e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                throw DeliveryRateLimitedException(e)
            }
            throw e
        }
        return PartnerEventProtocol.parseResults(data)
    }

    companion object {
        /** AD-17b: same region as every other accountability callable. */
        const val REGION = "us-central1"
    }
}
