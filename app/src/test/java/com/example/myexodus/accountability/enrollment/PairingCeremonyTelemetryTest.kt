package com.example.myexodus.accountability.enrollment

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §14 bullet 1 (task #37): the wire vocabulary is a query contract — Crashlytics-side counts
 * group by these exact strings, so a silent rename would split a metric's history. Pinned here.
 */
class PairingCeremonyTelemetryTest {

    @Test
    fun everyOutcome_reportsTheDocumentedWireString() {
        val events = mutableListOf<String>()
        val telemetry = PairingCeremonyTelemetry { events += it }

        telemetry.outcome(PairingCeremonyTelemetry.Outcome.PAIRED)
        telemetry.outcome(PairingCeremonyTelemetry.Outcome.SAS_MISMATCH)
        telemetry.outcome(PairingCeremonyTelemetry.Outcome.EXPIRED)
        telemetry.outcome(PairingCeremonyTelemetry.Outcome.TOKEN_USED)
        telemetry.outcome(PairingCeremonyTelemetry.Outcome.CANCELLED)
        telemetry.outcome(PairingCeremonyTelemetry.Outcome.FAILED)

        assertEquals(
            listOf(
                "pairing_ceremony_outcome outcome=paired",
                "pairing_ceremony_outcome outcome=sas_mismatch",
                "pairing_ceremony_outcome outcome=expired",
                "pairing_ceremony_outcome outcome=token_used",
                "pairing_ceremony_outcome outcome=cancelled",
                "pairing_ceremony_outcome outcome=failed",
            ),
            events,
        )
    }
}
