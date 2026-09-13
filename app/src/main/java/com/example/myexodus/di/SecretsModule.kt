package com.example.myexodus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.example.myexodus.secrets.SecretsRepository
import com.example.myexodus.secrets.SecretsStatus
import com.example.myexodus.secrets.TinkSecretsRepository
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

/**
 * §8.6 secrets vault wiring. The Tink keyset is wrapped by an Android Keystore master key
 * (Decision 12: not EncryptedSharedPreferences — Tink is the forward direction), so a
 * plaintext dump of Device-Protected Storage yields nothing (§17 AC: unrecoverable).
 */
@Module
@InstallIn(SingletonComponent::class)
object SecretsModule {

    @Provides @Singleton
    fun provideSecretsStatus(): MutableStateFlow<SecretsStatus> = MutableStateFlow(SecretsStatus.OK)

    @Provides @Singleton
    fun provideSecretsDataStore(
        @ApplicationContext context: Context,
        status: MutableStateFlow<SecretsStatus>,
    ): DataStore<Preferences> {
        // RegistryConfiguration-based APIs (generateNew, getPrimitive) need the AEAD key
        // creators registered once; AndroidKeysetManager's builder does not do it for us.
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        val aead = keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val aeadSerializer = AeadSerializer(
            aead = aead,
            wrappedSerializer = PreferencesFileSerializer,
            associatedData = DATA_STORE_FILE_NAME.encodeToByteArray(),
        )
        return DataStoreFactory.create(
            storage = FileStorage(
                serializer = aeadSerializer,
                produceFile = { context.applicationContext.preferencesDataStoreFile(DATA_STORE_FILE_NAME) },
            ),
            corruptionHandler = ReplaceFileCorruptionHandler {
                status.value = SecretsStatus.CORRUPTED
                emptyPreferences()
            },
        )
    }

    @Provides @Singleton
    fun provideSecretsRepository(
        dataStore: DataStore<Preferences>,
        status: MutableStateFlow<SecretsStatus>,
    ): SecretsRepository = TinkSecretsRepository(dataStore, status)

    private const val DATA_STORE_FILE_NAME = "myexodus_secrets.pb"
    private const val KEYSET_NAME = "myexodus_secrets_keyset"
    private const val KEYSET_PREFS = "myexodus_secrets_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://myexodus_secrets_master_key"
}
