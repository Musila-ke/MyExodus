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
class PartnerRolloutGate(private val remoteValue: suspend (key: String) -> Boolean) {

    suspend fun pushRegistrationEnabled(): Boolean =
        runCatching { remoteValue(PUSH_REGISTRATION_CONFIG_KEY) }.getOrDefault(true)

    /** §8.7 `delivery_client_sync_v1_enabled`: paused keeps local recording and only stops
     *  transmission, so a console flip never discards a recorded event. */
    suspend fun clientSyncEnabled(): Boolean =
        runCatching { remoteValue(DELIVERY_CLIENT_SYNC_CONFIG_KEY) }.getOrDefault(true)

    /** §13.3 `delivery_producer_rules_v1_enabled`: the opposite polarity of the kill switches
     *  — the shipped legacy transport stays active until the signed path is proven, so the
     *  default (and any fetch failure) is OFF, never an accidental transport switch. */
    suspend fun ruleProducerStagedEnabled(): Boolean =
        runCatching { remoteValue(DELIVERY_PRODUCER_RULES_CONFIG_KEY) }.getOrDefault(false)
}

/** The Remote Config key this gate reads; set it to `false` in the console to halt the rollout. */
internal const val PUSH_REGISTRATION_CONFIG_KEY = "partner_push_registration_enabled"

/** §8.7 Phase 7 rollout control, default ON (kill switch): paused = record locally, send nothing. */
internal const val DELIVERY_CLIENT_SYNC_CONFIG_KEY = "delivery_client_sync_v1_enabled"

/** §13.3 staged flag, default OFF: rule-lifecycle rows move to the signed transport only
 *  when this is explicitly on. */
internal const val DELIVERY_PRODUCER_RULES_CONFIG_KEY = "delivery_producer_rules_v1_enabled"
