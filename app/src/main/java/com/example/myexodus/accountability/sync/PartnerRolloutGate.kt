package com.example.myexodus.accountability.sync

/**
 * Plan §13 line 564: `push_installations` registration is gated behind a feature flag,
 * independent of app-store release cadence — Remote Config (DI wiring), read on every sync
 * pass. Kill-switch semantics, default ON: there is no production install base to stage
 * across, and default-off would silently stop a working registration rail on every existing
 * device; the flag's real job today is letting a rollout be halted without shipping an APK.
 * A fetch/read failure reads as enabled — the gate degrades to today's behavior, never to a
 * broken one (same shape as AD-18a's never-throws registrar contract).
 *
 * SOS §8.20 (Step 8): the same gate carries `partner_sos_enabled`, with the opposite failure
 * default — a new feature whose read fails stays OFF (never a surprise surface on).
 */
class PartnerRolloutGate(private val remoteValue: suspend (key: String) -> Boolean) {

    suspend fun pushRegistrationEnabled(): Boolean =
        runCatching { remoteValue(PUSH_REGISTRATION_CONFIG_KEY) }.getOrDefault(true)

    suspend fun sosEnabled(): Boolean =
        runCatching { remoteValue(PARTNER_SOS_CONFIG_KEY) }.getOrDefault(false)
}

/** The Remote Config key this gate reads; set it to `false` in the console to halt the rollout. */
internal const val PUSH_REGISTRATION_CONFIG_KEY = "partner_push_registration_enabled"

/** SOS §8.20 / FR-1: set to `true` in the console to enable the Reach out control. */
internal const val PARTNER_SOS_CONFIG_KEY = "partner_sos_enabled"
