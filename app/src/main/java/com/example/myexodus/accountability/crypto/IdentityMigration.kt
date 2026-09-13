package com.example.myexodus.accountability.crypto

import com.example.myexodus.accountability.enrollment.EnrollmentDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §7.6/§9/§8.2 Identity Migration: triggered when the signing key is permanently
 * invalidated ([KeyPermanentlyInvalidatedException] from signer operations) or when
 * [BiometricEnrollmentMonitor] reports an enrollment change. Rotates both hardware keys,
 * advances the identity epoch, and clears every enrollment — the old identity is revoked
 * (its chains are sealed, the partner can no longer verify new events from it) and each
 * pair requires a fresh transcript-bound enrollment. Rotation IS revocation here: the
 * protocol has no PKI; a superseded key id is simply never used again.
 */
@Singleton
class IdentityMigration @Inject constructor(
    private val enrollmentDao: EnrollmentDao,
    private val epochStore: IdentityEpochStore,
) {

    sealed interface MigrationResult {
        data class Migrated(val epoch: Int, val reEnrollmentRequired: Int) : MigrationResult
        data class Failed(val reason: String) : MigrationResult
    }

    /** [rotateKeys] is the Keystore-backed rotation; tests inject a plain-key substitute. */
    suspend fun migrate(rotateKeys: () -> Unit): MigrationResult {
        runCatching { rotateKeys() }.getOrElse { return MigrationResult.Failed(it.message ?: "rotation failed") }
        val epoch = epochStore.bump()
        val pairs = enrollmentDao.all()
        pairs.forEach { enrollmentDao.delete(it) }
        return MigrationResult.Migrated(epoch = epoch, reEnrollmentRequired = pairs.size)
    }
}
