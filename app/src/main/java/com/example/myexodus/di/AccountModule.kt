package com.example.myexodus.di

import com.example.myexodus.account.AuthOperations
import com.example.myexodus.account.FirebaseAuthOperations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release 1 (account plan §4.1): binds the Firebase seam behind [com.example.myexodus.account.AuthCoordinator]. */
@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    @Provides
    @Singleton
    fun provideAuthOperations(): AuthOperations = FirebaseAuthOperations()
}
