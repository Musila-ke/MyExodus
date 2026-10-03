package com.example.myexodus.accountability.sync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan §8 Step 7 (client) / AD-18a / §8.12 (SOS Step 5). The registrar's collaborators are
 *  fakes — no Firebase, no FCM — so the orchestration rules are pinned: nothing writes
 *  without uid + FID + token, the write lands under (uid, FID) with the exact rules key set
 *  including the pushEligible snapshot, the legacy ANDROID_ID document is removed only
 *  AFTER the replacement landed, and no failure path throws (registration is best-effort;
 *  AD-05 sync still delivers). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushInstallationRegistrarTest {

    private data class Write(val uid: String, val installationId: String, val data: Map<String, Any>)

    private class Fake(
        val uid: String? = "uid-a",
        val fid: String? = "fid-1",
        val token: String? = "tok-1",
        val eligible: Boolean = true,
    ) {
        val writes = mutableListOf<Write>()
        val legacyDeletes = mutableListOf<Pair<String, String>>()
        var uidCalls = 0
        var fidCalls = 0
        var tokenCalls = 0

        fun registrar(
            writeFails: Boolean = false,
            uidThrows: Boolean = false,
            fidThrows: Boolean = false,
            tokenThrows: Boolean = false,
            deleteLegacyFails: Boolean = false,
            legacyInstallationId: String? = "android-id-legacy",
        ) = PushInstallationRegistrar(
            ensureInstallationId = {
                fidCalls++
                if (fidThrows) throw IllegalStateException("installations down")
                fid
            },
            platform = "android",
            appVersion = "9.9",
            ensureUid = {
                uidCalls++
                if (uidThrows) throw IllegalStateException("auth down")
                uid
            },
            fetchToken = {
                tokenCalls++
                if (tokenThrows) throw IllegalStateException("fcm down")
                token
            },
            pushEligible = { eligible },
            write = { uid, installationId, data ->
                if (writeFails) throw IllegalStateException("permission denied")
                writes += Write(uid, installationId, data)
            },
            legacyInstallationId = legacyInstallationId,
            deleteLegacy = { uid, installationId ->
                if (deleteLegacyFails) throw IllegalStateException("delete denied")
                legacyDeletes += uid to installationId
            },
        )
    }

    @Test
    fun register_writesTheInstallationUnderUidAndFidWithTheRulesKeySet() = runBlocking {
        val fake = Fake()
        assertTrue(fake.registrar().register())

        val write = fake.writes.single()
        assertEquals("uid-a", write.uid)
        assertEquals("fid-1", write.installationId)
        assertEquals(
            setOf("provider", "token", "platform", "appVersion", "pushEligible", "updatedAt", "createdAt"),
            write.data.keys,
        )
        assertEquals("fcm", write.data["provider"])
        assertEquals("tok-1", write.data["token"])
        assertEquals("android", write.data["platform"])
        assertEquals("9.9", write.data["appVersion"])
        assertEquals(true, write.data["pushEligible"])
    }

    @Test
    fun register_carriesAPushIneligibleSnapshotWithoutGatingTheWrite() = runBlocking {
        val fake = Fake(eligible = false)
        assertTrue(fake.registrar().register())
        assertEquals(false, fake.writes.single().data["pushEligible"])
    }

    @Test
    fun register_withoutUid_skipsTheWriteWithoutFetchingAnythingElse() = runBlocking {
        val fake = Fake(uid = null)
        assertFalse(fake.registrar().register())
        assertEquals(0, fake.writes.size)
        assertEquals(0, fake.fidCalls) // no point resolving an identity for a device that cannot claim it
        assertEquals(0, fake.tokenCalls)
    }

    @Test
    fun register_withoutFid_skipsTheWriteWithoutFetchingAToken() = runBlocking {
        val fake = Fake(fid = null)
        assertFalse(fake.registrar().register())
        assertEquals(0, fake.writes.size)
        assertEquals(0, fake.tokenCalls)
        assertEquals(0, fake.legacyDeletes.size) // nothing replaced it, so nothing may remove it
    }

    @Test
    fun register_withoutToken_skipsTheWrite() = runBlocking {
        val fake = Fake(token = null)
        assertFalse(fake.registrar().register())
        assertEquals(0, fake.writes.size)
    }

    @Test
    fun register_withBlankToken_skipsTheWrite() = runBlocking {
        val fake = Fake(token = "   ")
        assertFalse(fake.registrar().register())
        assertEquals(0, fake.writes.size)
    }

    @Test
    fun register_deletesTheLegacyDocumentOnceWhenItDiffersFromTheFid() = runBlocking {
        val fake = Fake()
        assertTrue(fake.registrar().register())
        assertEquals(listOf("uid-a" to "android-id-legacy"), fake.legacyDeletes)
    }

    @Test
    fun register_neverDeletesTheDocumentItJustWrote() = runBlocking {
        val fake = Fake(fid = "same-id")
        assertTrue(fake.registrar(legacyInstallationId = "same-id").register())
        assertEquals(0, fake.legacyDeletes.size)
    }

    @Test
    fun register_withoutALegacyId_deletesNothing() = runBlocking {
        val fake = Fake()
        assertTrue(fake.registrar(legacyInstallationId = null).register())
        assertEquals(0, fake.legacyDeletes.size)
    }

    @Test
    fun register_keepsTheLegacyDocument_whenTheReplacementWriteFailed() = runBlocking {
        val fake = Fake()
        assertFalse(fake.registrar(writeFails = true).register())
        assertEquals(0, fake.legacyDeletes.size) // removing first would leave the device unregistered
    }

    @Test
    fun register_succeeds_whenOnlyTheLegacyCleanupFails() = runBlocking {
        val fake = Fake()
        assertTrue(fake.registrar(deleteLegacyFails = true).register())
        assertEquals(1, fake.writes.size)
    }

    @Test
    fun register_neverThrows_whenAnyStepFails() = runBlocking {
        assertFalse(Fake().registrar(uidThrows = true).register())
        assertFalse(Fake().registrar(fidThrows = true).register())
        assertFalse(Fake().registrar(tokenThrows = true).register())
        assertFalse(Fake().registrar(writeFails = true).register())
    }
}

/** §8.12 pushEligible formula against the real (Robolectric) NotificationManager: the
 *  snapshot is enabled AND channel-exists AND importance != NONE. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushEligibleOfTest {

    private val context: Application = org.robolectric.RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun createChannel(importance: Int) {
        manager.createNotificationChannel(
            NotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES, "Nudges", importance),
        )
    }

    @Test
    fun eligible_onlyOnceTheChannelExistsWithNonNoneImportance() {
        assertFalse("no channel yet", PushInstallationRegistrar.pushEligibleOf(context))

        // The real startup creator, not a hand-rolled channel: §8.14's channel is what makes
        // a fresh install push-eligible (§8.12).
        PartnerNotifications.ensurePartnerNudgesChannel(context)
        assertTrue(PushInstallationRegistrar.pushEligibleOf(context))

        // The user silencing the channel is the one thing that un-eligibles this device.
        createChannel(NotificationManager.IMPORTANCE_NONE)
        assertFalse(PushInstallationRegistrar.pushEligibleOf(context))
    }
}
