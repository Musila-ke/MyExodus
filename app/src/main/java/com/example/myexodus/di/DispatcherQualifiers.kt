package com.example.myexodus.di

import javax.inject.Qualifier

/** Blocking I/O (DataStore, file and database reads) behind a ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** CPU work (Argon2id, sealing) behind a ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WorkDispatcher
