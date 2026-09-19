package com.example.myexodus.accountability.sync

import android.app.Application
import com.example.myexodus.accountability.state.PendingOutboxRow
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Plan §7 item 9 / §6 steps 3+8: the drain writes the **exact stored envelope bytes** under
 * `messageId`, marks SENT only after the write lands, and leaves failures PENDING for the
 * next run (§9 row 1). §12 unit anchors this: "retrying a SyncWorker send transmits
 * byte-identical envelope content, never regenerated ciphertext/signature."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailboxOutboxDrainerTest {

    private data class Write(val partnerUid: String, val messageId: String, val data: Map<String, Any>)

    private class Fake(private val rows: List<PendingOutboxRow>) {
        val writes = mutableListOf<Write>()
        val sent = mutableListOf<String>()
        val attempts = mutableListOf<Pair<String, Long>>()
        val latencies = mutableListOf<Long>()
        var readError: Exception? = null
        var writeError: ((messageId: String) -> Exception?)? = null
        var clock = 42L
        var advancePerWrite = 0L

        val drainer = MailboxOutboxDrainer(
            pendingRows = { _, _ -> readError?.let { throw it } ?: rows },
            markSent = { sent += it },
            recordAttempt = { messageId, at -> attempts += messageId to at },
            write = { partnerUid, messageId, data ->
                writeError?.invoke(messageId)?.let { throw it }
                writes += Write(partnerUid, messageId, data)
                clock += advancePerWrite
            },
            now = { clock },
            onSendLatency = { latencies += it },
        )
    }

    private fun row(
        id: String,
        bytes: ByteArray,
        senderKeyId: String = "sender-1",
        epoch: Int = 3,
    ) = PendingOutboxRow(messageId = id, envelopeBytes = bytes, senderKeyId = senderKeyId, epoch = epoch)

    @Test
    fun drain_writesTheExactStoredBytes_inChainOrder_underMessageId() = runBlocking {
        val first = byteArrayOf(10, 20, 30)
        val second = byteArrayOf(40, 50)
        val fake = Fake(listOf(row("m1", first), row("m2", second, senderKeyId = "sender-2", epoch = 4)))

        val result = fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(2), result)
        assertEquals(listOf("m1", "m2"), fake.writes.map { it.messageId })
        assertEquals(listOf("uid-partner", "uid-partner"), fake.writes.map { it.partnerUid })

        val data = fake.writes[0].data
        // Exactly the rules' create hasOnly — any extra key is a rules rejection.
        assertEquals(setOf("senderId", "senderUid", "pairId", "payload", "keyEpoch", "timestamp"), data.keys)
        assertEquals("sender-1", data["senderId"])
        assertEquals("uid-me", data["senderUid"])
        assertEquals("pair-1", data["pairId"])
        assertEquals(3, data["keyEpoch"])
        assertTrue(data["timestamp"] is com.google.firebase.firestore.FieldValue)
        // Byte-identical: the transmitted payload decodes back to the stored envelope bytes,
        // never a regenerated ciphertext/signature (§12).
        assertArrayEquals(first, Base64.getDecoder().decode(data["payload"] as String))
        assertArrayEquals(second, Base64.getDecoder().decode(fake.writes[1].data["payload"] as String))
        assertEquals(4, fake.writes[1].data["keyEpoch"])

        assertEquals(listOf("m1", "m2"), fake.sent)
        assertTrue(fake.attempts.isEmpty())
    }

    @Test
    fun drain_whenAWriteFails_stopsLeavesThatRowPending_andSkipsTheRest() = runBlocking {
        val fake = Fake(listOf(row("m1", byteArrayOf(1)), row("m2", byteArrayOf(2)), row("m3", byteArrayOf(3))))
        fake.writeError = { if (it == "m2") IllegalStateException("offline") else null }

        val result = fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        assertEquals("offline", (result as MailboxOutboxDrainer.DrainResult.Failed).reason)
        assertEquals(listOf("m1"), fake.writes.map { it.messageId })
        assertEquals(listOf("m1"), fake.sent)
        assertEquals(listOf("m2" to 42L), fake.attempts)
    }

    @Test
    fun drain_permissionDenied_leavesTheRowPending_andReportsPermissionDenied() = runBlocking {
        val fake = Fake(listOf(row("m1", byteArrayOf(1))))
        fake.writeError = { FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED) }

        val result = fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        // Documented deviation from v1's terminal reading: retry is the only option that
        // never drops a message (pairing-not-active-yet vs replay are indistinguishable).
        assertSame(MailboxOutboxDrainer.DrainResult.PermissionDenied, result)
        assertTrue(fake.sent.isEmpty())
        assertEquals(listOf("m1" to 42L), fake.attempts)
    }

    @Test
    fun drain_alreadyExists_marksSent_withoutRecordingAnAttempt() = runBlocking {
        // A prior drain wrote the document but crashed before marking the row.
        val fake = Fake(listOf(row("m1", byteArrayOf(1))))
        fake.writeError = { FirebaseFirestoreException("exists", FirebaseFirestoreException.Code.ALREADY_EXISTS) }

        val result = fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(1), result)
        assertEquals(listOf("m1"), fake.sent)
        assertTrue(fake.attempts.isEmpty())
    }

    @Test
    fun drain_whenOutboxReadFails_reportsFailed_andWritesNothing() = runBlocking {
        val fake = Fake(listOf(row("m1", byteArrayOf(1))))
        fake.readError = IllegalStateException("db down")

        val result = fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        assertEquals("db down", (result as MailboxOutboxDrainer.DrainResult.Failed).reason)
        assertTrue(fake.writes.isEmpty())
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun drain_emptyOutbox_isSentZero() = runBlocking {
        assertEquals(MailboxOutboxDrainer.DrainResult.Sent(0), Fake(emptyList()).drainer.drain("pair-1", "uid-me", "uid-partner"))
    }

    @Test
    fun drain_samplesT1LatencyPerWrittenMessage_onlyOnSuccess() = runBlocking {
        // §14/§11 T1: attempt → Firestore confirmed. The write advances the fake clock, so the
        // sample is the seam's now() delta around the write, per message.
        val fake = Fake(listOf(row("m1", byteArrayOf(1)), row("m2", byteArrayOf(2))))
        fake.advancePerWrite = 1500
        fake.writeError = { if (it == "m2") IllegalStateException("offline") else null }

        fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        assertEquals("only the successful write is sampled", listOf(1500L), fake.latencies)
    }

    @Test
    fun drain_noLatencySample_whenNowersAreWritten() = runBlocking {
        val fake = Fake(listOf(row("m1", byteArrayOf(1))))
        fake.readError = IllegalStateException("db down")

        fake.drainer.drain("pair-1", "uid-me", "uid-partner")

        assertTrue(fake.latencies.isEmpty())
    }
}
