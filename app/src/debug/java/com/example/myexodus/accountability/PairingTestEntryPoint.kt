package com.example.myexodus.accountability

import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.secrets.SecretsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Test access to the §10 pairing singletons so the on-device harness exercises the exact
 * instances the app's real flows use (same pattern as EnhancedModeTestEntryPoint).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PairingTestEntryPoint {
    fun pairingFlow(): PairingFlow
    fun keystore(): AccountabilityKeyStore
    fun enrollmentDao(): EnrollmentDao
    fun secretsRepository(): SecretsRepository
    fun deletion(): BilateralDeletion
}
