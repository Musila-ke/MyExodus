package com.example.myexodus.accountability.sync

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan §8 Step 7 (client) / AD-18a. The registrar's collaborators are fakes — no Firebase,
 *  no FCM — so the orchestration rules are pinned: nothing writes without both a uid and a
 *  token, the write lands under (uid, installationId) with the exact rules key set, and no
 *  failure path throws (registration is best-effort; AD-05 sync still delivers). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushInstallationRegistrarTest {

    private data class Write(val uid: String, val installationId: String, val data: Map<String, Any>)

    private class Fake(val uid: String? = "uid-a", val token: String? = "tok-1") {
        val writes = mutableListOf<Write>()
        var uidCalls = 0
        var tokenCalls = 0

        fun registrar(
            writeFails: Boolean = false,
            uidThrows: Boolean = false,
            tokenThrows: Boolean = false,
        ) = PushInstallationRegistrar(
            installationId = "abcd1234",
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
            write = { uid, installationId, data ->
                if (writeFails) throw IllegalStateException("permission denied")
                writes += Write(uid, installationId, data)
            },
        )
    }

    @Test
    fun register_writesTheInstallationUnderUidWithTheRulesKeySet() = runBlocking {
        val fake = Fake()
        assertTrue(fake.registrar().register())

        val write = fake.writes.single()
        assertEquals("uid-a", write.uid)
        assertEquals("abcd1234", write.installationId)
        assertEquals(
            setOf("provider", "token", "platform", "appVersion", "updatedAt", "createdAt"),
            write.data.keys,
        )
        assertEquals("fcm", write.data["provider"])
        assertEquals("tok-1", write.data["token"])
        assertEquals("android", write.data["platform"])
        assertEquals("9.9", write.data["appVersion"])
    }

    @Test
    fun register_withoutUid_skipsTheWriteWithoutEvenFetchingAToken() = runBlocking {
        val fake = Fake(uid = null)
        assertFalse(fake.registrar().register())
        assertEquals(0, fake.writes.size)
        assertEquals(0, fake.tokenCalls) // no point resolving a token for a device that cannot claim it
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
    fun register_neverThrows_whenAnyStepFails() = runBlocking {
        assertFalse(Fake().registrar(uidThrows = true).register())
        assertFalse(Fake().registrar(tokenThrows = true).register())
        assertFalse(Fake().registrar(writeFails = true).register())
    }
}
