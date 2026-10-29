package com.example.myexodus.di

import android.content.Context
import com.example.myexodus.account.AuthOperations
import com.example.myexodus.account.FirebaseAuthOperations
import com.example.myexodus.account.GuestTransitionApi
import com.example.myexodus.account.GuestTransitionCallables
import com.example.myexodus.account.GuestTransitionJournal
import com.example.myexodus.account.KeystoreGuestTransitionJournal
import com.google.firebase.functions.FirebaseFunctions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release 1 (account plan §4.1/§8.4): the account seams behind the coordinators. */
@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    @Provides
    @Singleton
    fun provideAuthOperations(): AuthOperations = FirebaseAuthOperations()

    @Provides
    @Singleton
    fun provideGuestTransitionJournal(@ApplicationContext context: Context): GuestTransitionJournal =
        KeystoreGuestTransitionJournal(context)

    @Provides
    @Singleton
    fun provideGuestTransitionApi(functions: FirebaseFunctions): GuestTransitionApi =
        GuestTransitionCallables(functions)
}
