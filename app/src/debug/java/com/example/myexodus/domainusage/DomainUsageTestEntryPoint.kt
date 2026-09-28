package com.example.myexodus.domainusage

import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.AppRulesStoreFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Test access to the domain-usage singletons the §12.2 harness drives: the same
 * `DomainUsageEngine` instance the VPN service runs, the same `RulesRepository` the
 * RuleEditor writes through, the same provider the engine reads revisions from, and the
 * same PSL/store the editor validates and persists with.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DomainUsageTestEntryPoint {
    fun engine(): DomainUsageEngine
    fun rulesRepository(): RulesRepository
    fun domainRuleProvider(): DomainRuleProvider
    fun publicSuffixList(): PublicSuffixList
    fun appRulesStoreFactory(): AppRulesStoreFactory
}
