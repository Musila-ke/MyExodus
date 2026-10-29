package com.example.myexodus.accountability.rulecontrol

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerRuleChange
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8.4 RULE_CONTROL receive half: a verified rule-lifecycle body becomes the recipient-local
 * feed row — routed under the pair that delivered it, redelivery is a no-op, and expired rows
 * are swept out on the insert path (no worker). Other contract kinds are not this class's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleControlReceiverTest {

    private lateinit var context: Context
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private var nowMs = 1_800_000_000_000L
    private val ruleId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val health = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), health,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, health)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private fun receiver() = RuleControlReceiver(storeFactory = factory, now = { nowMs })

    private fun event(change: RuleChange = RuleChange.DISABLED) = AccountabilityEventWire.ruleControl(
        eventId = AccountabilityEventWire.newEventId(),
        sequence = 7,
        ruleId = ruleId,
        change = change,
        occurredAtMillis = nowMs,
    )

    private suspend fun rows(pairId: String) =
        factory.open()!!.partnerRuleChangeDao().recent(pairId, nowMs, 100)

    @Test
    fun ruleControlEvent_becomesTheRecipientFeedRow() = runTest {
        assertTrue(receiver().onEvent("pair-1", event()))

        val row = rows("pair-1").single()
        assertEquals(ruleId.toString(), row.ruleId)
        assertEquals(RuleChange.DISABLED.wire, row.ruleChange)
        assertEquals(AccountabilityEventWire.minuteOf(nowMs), row.occurredAtMinute)
        assertEquals(nowMs, row.receivedAtMs)
        assertEquals(nowMs + PartnerEventJournal.RETENTION_MILLIS, row.expiresAtMs)
        assertTrue("another pair's feed is never written", rows("pair-2").isEmpty())
    }

    @Test
    fun redeliveredEvent_isANoOp_andKeepsTheFirstRow() = runTest {
        val receiver = receiver()
        val delivered = event(RuleChange.ASSIGNED)

        assertTrue(receiver.onEvent("pair-1", delivered))
        assertTrue(receiver.onEvent("pair-1", delivered))

        assertEquals(1, rows("pair-1").size)
    }

    @Test
    fun nonRuleControlKinds_areIgnored() = runTest {
        val accountability = AccountabilityEventWire.accountability(
            eventId = AccountabilityEventWire.newEventId(),
            sequence = 1,
            category = AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
            occurredAtMillis = nowMs,
        )

        assertFalse(receiver().onEvent("pair-1", accountability))
        assertTrue(rows("pair-1").isEmpty())
    }

    @Test
    fun insertSweepsExpiredRows_onlyAfterTheirWindow() = runTest {
        val dao = factory.open()!!.partnerRuleChangeDao()
        dao.insert(
            PartnerRuleChange(
                pairId = "pair-1",
                eventId = UUID.randomUUID().toString(),
                ruleId = ruleId.toString(),
                ruleChange = RuleChange.CREATED.wire,
                occurredAtMinute = 1,
                receivedAtMs = nowMs - PartnerEventJournal.RETENTION_MILLIS - 1,
                expiresAtMs = nowMs - 1,
            ),
        )
        assertEquals("the stale row is invisible before any insert", 0, rows("pair-1").size)

        assertTrue(receiver().onEvent("pair-1", event()))

        val remaining = rows("pair-1").single()
        assertEquals(RuleChange.DISABLED.wire, remaining.ruleChange)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
