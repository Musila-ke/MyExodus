package com.example.myexodus.secrets

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * §8.6/§9/§12: the secrets vault stores pairing key + escape code behind a Tink AEAD
 * DataStore; the Keystore-loss path (a different keyset that cannot decrypt the stored
 * ciphertext — the same failure a lost Android Keystore master key produces) surfaces as
 * SecretsStatus.CORRUPTED and self-heals to empty instead of crashing or locking out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TinkSecretsRepositoryTest {

    @Test
    fun storeAndRead_pairingKeyRoundTrips() = runTest {
        val (repository, _) = secrets()
        assertNull(repository.pairingKey.first())
        repository.storePairingKey("partner-credential".encodeToByteArray())
        assertArrayEquals("partner-credential".encodeToByteArray(), repository.pairingKey.first())
    }

    @Test
    fun storeAndRead_escapeCodeRoundTrips() = runTest {
        val (repository, _) = secrets()
        assertNull(repository.escapeCode.first())
        repository.storeEscapeCode("escape-1984".encodeToByteArray())
        assertArrayEquals("escape-1984".encodeToByteArray(), repository.escapeCode.first())
    }

    @Test
    fun status_isOkBeforeAnyCorruption() = runTest {
        val (repository, status) = secrets()
        assertEquals(SecretsStatus.OK, status.value)
        assertEquals(SecretsStatus.OK, repository.status.first())
    }

    /** §12 Keystore-loss test: undecryptable ciphertext → corrupted state → recovery, not crash/lockout. */
    @Test
    fun keystoreKeyLoss_surfacesCorruptedState_andAllowsRepair() = runTest {
        val fileA = File.createTempFile("secrets_keyloss_a_", ".pb")
        val keysetA = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val keysetB = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)

        val (repoA, _) = secrets(fileA, keysetA)
        repoA.storeEscapeCode("original".encodeToByteArray())
        assertArrayEquals("original".encodeToByteArray(), repoA.escapeCode.first())

        // Simulated key loss: the encrypted file survives (copied to a fresh path — the
        // on-device situation is exactly this: ciphertext on disk, Keystore key gone), but
        // the new master keyset (B) cannot decrypt it.
        val fileB = File.createTempFile("secrets_keyloss_b_", ".pb")
        fileA.copyTo(fileB, overwrite = true)
        val (repoB, statusB) = secrets(fileB, keysetB)
        assertNull(repoB.escapeCode.first()) // triggers the failing read
        assertEquals(SecretsStatus.CORRUPTED, statusB.first())

        // Recovery: re-pairing / re-coding works; the corrupted flag clears on write.
        repoB.storeEscapeCode("new-code".encodeToByteArray())
        assertEquals(SecretsStatus.OK, statusB.first())
        assertArrayEquals("new-code".encodeToByteArray(), repoB.escapeCode.first())

        fileA.delete()
        fileB.delete()
    }

    private fun secrets(
        file: File = File.createTempFile("secrets_", ".pb"),
        keyset: KeysetHandle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM),
    ): Pair<TinkSecretsRepository, MutableStateFlow<SecretsStatus>> {
        val aead: Aead = keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val status = MutableStateFlow(SecretsStatus.OK)
        val dataStore: DataStore<Preferences> = DataStoreFactory.create(
            storage = FileStorage(
                serializer = aeadSerializer(aead),
                produceFile = { file },
            ),
            corruptionHandler = ReplaceFileCorruptionHandler {
                status.value = SecretsStatus.CORRUPTED
                emptyPreferences()
            },
        )
        return TinkSecretsRepository(dataStore, status) to status
    }

    private fun aeadSerializer(aead: Aead) = AeadSerializer(
        aead = aead,
        wrappedSerializer = PreferencesFileSerializer,
        associatedData = "myexodus_secrets.pb".encodeToByteArray(),
    )

    companion object {
        init {
            // Same registration the production module performs. Runs at class load: the
            // secrets() default-arg KeysetHandle.generateNew is evaluated before any body.
            AeadConfig.register()
        }
    }
}
