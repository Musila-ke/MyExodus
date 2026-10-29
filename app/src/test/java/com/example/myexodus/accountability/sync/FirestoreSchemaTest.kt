package com.example.myexodus.accountability.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.PairingToken
import com.google.firebase.firestore.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirestoreSchemaTest {

    @Test
    fun pairingTokenDocumentId_isDeterministicAndDistinctPerToken() {
        val token = PairingToken.generate(1_700_000_000_000L)
        val a = FirestoreSchema.pairingTokenDocumentId(token)
        assertEquals(a, FirestoreSchema.pairingTokenDocumentId(token))
        assertEquals(64, a.length) // hex sha256
        assertNotEquals(a, FirestoreSchema.pairingTokenDocumentId(PairingToken.generate(1_700_000_000_000L)))
    }

    @Test
    fun mailboxMessageData_carriesExactlyTheRulesKeySet() {
        val data = FirestoreSchema.mailboxMessageData(
            senderId = "sender-1",
            senderUid = "uid-a",
            pairId = "pair-1",
            payloadBase64 = "AQID",
            keyEpoch = 2,
        )
        // firestore.rules: mailbox messages create hasOnly(senderId, senderUid, pairId,
        // payload, keyEpoch, timestamp) — any extra key is a rules rejection.
        assertEquals(setOf("senderId", "senderUid", "pairId", "payload", "keyEpoch", "timestamp"), data.keys)
        assertEquals("sender-1", data["senderId"])
        assertEquals("uid-a", data["senderUid"]) // rule: senderUid == request.auth.uid
        assertEquals("pair-1", data["pairId"])
        assertEquals("AQID", data["payload"])
        assertEquals(2, data["keyEpoch"])
        assertTrue(data["timestamp"] is FieldValue) // rules: timestamp == request.time
    }

    @Test
    fun mailboxAckData_isExactlyTheAckedAtKey() {
        val data = FirestoreSchema.mailboxAckData()
        // firestore.rules: acks create hasOnly(['ackedAt']), ackedAt == request.time.
        assertEquals(setOf("ackedAt"), data.keys)
        assertTrue(data["ackedAt"] is FieldValue)
    }

    @Test
    fun nudgeSettingsData_isExactlyTheRulesKeySet() {
        // firestore.rules: nudgeSettings create/update allows exactly blocked + updatedAt,
        // updatedAt == request.time. Any extra key fails every write (the same contract the
        // four other writers above are pinned to).
        val data = FirestoreSchema.nudgeSettingsData(blocked = true)
        assertEquals(setOf("blocked", "updatedAt"), data.keys)
        assertEquals(true, data["blocked"])
        assertTrue(data["updatedAt"] is FieldValue)
    }

    @Test
    fun nudgeAckData_isExactlyTheAckedAtKey() {
        // firestore.rules: nudge acks create hasOnly(['ackedAt']), ackedAt == request.time.
        val data = FirestoreSchema.nudgeAckData()
        assertEquals(setOf("ackedAt"), data.keys)
        assertTrue(data["ackedAt"] is FieldValue)
    }

    @Test
    fun pushInstallationData_carriesExactlyTheRulesKeySet() {
        val data = FirestoreSchema.pushInstallationData(
            provider = "fcm",
            token = "tok-1",
            platform = "android",
            appVersion = "1.0",
            pushEligible = true,
        )
        // firestore.rules: push_installations create/update hasOnly(provider, token, platform,
        // appVersion, pushEligible, updatedAt, createdAt) — any extra key is a rules rejection.
        assertEquals(
            setOf("provider", "token", "platform", "appVersion", "pushEligible", "updatedAt", "createdAt"),
            data.keys,
        )
        assertEquals("fcm", data["provider"])
        assertEquals("tok-1", data["token"])
        assertEquals("android", data["platform"])
        assertEquals("1.0", data["appVersion"])
        assertEquals(true, data["pushEligible"])
        assertTrue(data["updatedAt"] is FieldValue) // rules: updatedAt == request.time
        assertTrue(data["createdAt"] is FieldValue)
    }
}
