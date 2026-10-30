package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.data.db.entity.HabitProgress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The R2.13 seam in one flow: the committed key bundle unlocks the ABK, the ABK seals a real
 * compressed payload into the v1 object, and the same ABK restores the exact logical snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupSnapshotPipelineTest {

    private lateinit var manager: BackupKeyManager
    private val uid = "uid-fixture"
    private val passphrase = "7Gx!q2-Lm9@Rv4#Tz8"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = BackupKeyManager(
            api = object : BackupKeyApi {
                private var stored: RemoteBundle? = null
                override suspend fun read(uid: String): RemoteBundle? = stored
                override suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle {
                    val remote = RemoteBundle(1, bundle)
                    stored = remote
                    return remote
                }
                override suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle {
                    val remote = RemoteBundle(expectedRevision + 1, bundle)
                    stored = remote
                    return remote
                }
            },
            revisions = object : BackupRevisionStore {
                private val revisions = mutableMapOf<String, Long>()
                override suspend fun highest(uid: String): Long? = revisions[uid]
                override suspend fun record(uid: String, revision: Long) {
                    revisions[uid] = revision
                }
            },
            wordlists = BackupWordlists(context),
        )
    }

    private val snapshot = GuestSnapshot(
        habits = listOf(
            HabitProgress(
                progressId = "h1",
                profileId = "profile-1",
                targetIdentifier = "Porn",
                currentCleanDaysStreak = 2,
                totalRelapses = 3,
                accessAttemptsCount = 4,
                habitDurationYears = 5,
                onboardingSeverityLevel = "MODERATE",
                lastRelapseTimestamp = null,
                assignedPartnershipId = null,
                allTimeMaxStreakElapsedMs = 86_400_000L,
                isSelected = true,
            ),
        ),
        streaks = emptyList(),
        milestones = emptyList(),
        attempts = emptyList(),
        sessions = emptyList(),
        coaching = emptyList(),
        riskWindows = emptyList(),
        policy = null,
    )

    @Test
    fun committedBundleSealsAndRestoresTheSnapshot() = runBlocking {
        val created = manager.create(uid, passphrase)
        val backupId = BackupCrypto.random(BackupEnvelope.BACKUP_ID_BYTES)

        manager.withAbk(
            uid,
            created.remote,
            BackupKeyManager.Unlock.Passphrase(passphrase),
        ) { abk ->
            val compressed = ByteArrayOutputStream()
            val sizes = BackupPayload.write(snapshot, compressed)
            val compressedBytes = compressed.toByteArray()

            val objectBytes = ByteArrayOutputStream()
            val sealed = BackupSnapshotCipher.seal(
                compressed = ByteArrayInputStream(compressedBytes),
                compressedLength = sizes.compressedBytes,
                abk = abk,
                uid = uid,
                backupId = backupId,
                keyId = created.remote.bundle.keyId,
                schemaVersion = 20,
                out = objectBytes,
            )
            assertArrayEquals(created.remote.bundle.keyId, sealed.header.keyId)

            val staging = ByteArrayOutputStream()
            val opened = BackupSnapshotCipher.open(
                ByteArrayInputStream(objectBytes.toByteArray()),
                abk,
                uid,
                staging,
            )
            assertTrue(opened is BackupSnapshotCipher.OpenResult.Opened)
            assertEquals(
                sizes.compressedBytes,
                (opened as BackupSnapshotCipher.OpenResult.Opened)
                    .header.payloadPlaintextLength,
            )

            val restored = RestoringSink()
            BackupPayload.read(
                ByteArrayInputStream(staging.toByteArray()),
                staging.size().toLong(),
                restored,
            )
            assertEquals(snapshot, restored.snapshot())
        }
    }

    private class RestoringSink : SnapshotSink {
        private val habits = mutableListOf<HabitProgress>()
        override fun habit(value: HabitProgress) { habits += value }
        override fun streak(value: com.example.myexodus.data.db.entity.RecoveryStreak) = Unit
        override fun milestone(value: com.example.myexodus.data.db.entity.MilestoneEvent) = Unit
        override fun attempt(value: com.example.myexodus.data.db.entity.AttemptLog) = Unit
        override fun session(value: com.example.myexodus.data.db.entity.RecoverySession) = Unit
        override fun coaching(value: com.example.myexodus.data.db.entity.CoachingStep) = Unit
        override fun riskWindow(value: com.example.myexodus.data.db.entity.RiskWindow) = Unit
        override fun policy(value: com.example.myexodus.data.db.entity.RecoveryPolicy) = Unit

        fun snapshot() = GuestSnapshot(
            habits = habits,
            streaks = emptyList(),
            milestones = emptyList(),
            attempts = emptyList(),
            sessions = emptyList(),
            coaching = emptyList(),
            riskWindows = emptyList(),
            policy = null,
        )
    }
}
