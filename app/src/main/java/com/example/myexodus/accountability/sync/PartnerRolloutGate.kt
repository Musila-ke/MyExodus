package com.example.myexodus.accountability.sync

/**
 * Plan §13 line 564: `push_installations` registration is gated behind a feature flag,
 * independent of app-store release cadence — Remote Config (DI wiring), read on every sync
 * pass. Kill-switch semantics, default ON: there is no production install base to stage
 * across, and default-off would silently stop a working registration rail on every existing
 * device; the flag's real job today is letting a rollout be halted without shipping an APK.
 * A fetch/read failure reads as enabled — the gate degrades to today's behavior, never to a
 * broken one (same shape as AD-18a's never-throws registrar contract).
 */
class PartnerRolloutGate(private val remoteValue: suspend () -> Boolean) {

    suspend fun pushRegistrationEnabled(): Boolean =
        runCatching { remoteValue() }.getOrDefault(true)
}

/** The Remote Config key this gate reads; set it to `false` in the console to halt the rollout. */
internal const val PUSH_REGISTRATION_CONFIG_KEY = "partner_push_registration_enabled"
