package com.example.myexodus.di

import javax.inject.Qualifier

/**
 * The one serialized dispatcher every accounting writer shares (enforcement plan v2 Decision 19).
 * It is a qualifier rather than an inline `Dispatchers.IO.limitedParallelism(1)` because two
 * separate `limitedParallelism` views of the same underlying pool carry *independent* limits —
 * two injections would have meant two lanes and the read-modify-write race Decision 19 exists to
 * close. Everything that touches the usage ledger or the tracker, including the ledger's own
 * maintenance writers, must run here.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountingDispatcher
