package com.example.myexodus.account

import android.content.Context
import android.util.Log
import com.example.myexodus.data.db.KeystoreWrappedDekManager
import com.example.myexodus.util.deleteOrWarn
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Account plan §6.2.3/§8.4: the encrypted local quarantine. Each record is one AES-256-GCM file
 * under `files/guest_quarantine`, keyed by the same Keystore-wrapped-DEK scheme every other
 * local store uses (Decision 12). File names are digests of the guest UID and transition id —
 * never a caller-supplied string — and writes go through a temp file + rename so a process
 * death can never leave a half-written copy that decodes as valid.
 *
 * The snapshot contains the guest's logical data; it is only ever written while the guest
 * session owns the database and only ever read back by the import coordinator after the server
 * bound the record to a target UID.
 */
@Singleton
class KeystoreGuestQuarantine @Inject constructor(
    @ApplicationContext context: Context,
) : GuestQuarantine {

    private val dir = File(context.filesDir, DIR_NAME)
    private val keys = KeystoreWrappedDekManager(context, KEK_ALIAS, PREFS_NAME)

    override suspend fun save(record: GuestQuarantineRecord): Boolean =
        withContext(Dispatchers.IO) { write(record) }

    override suspend fun load(guestUid: String): QuarantineLoad = withContext(Dispatchers.IO) {
        val prefix = guestHash(guestUid) + "-"
        val files = dir.listFiles()?.filter { it.name.startsWith(prefix) }.orEmpty()
        val records = mutableListOf<GuestQuarantineRecord>()
        var unreadable = 0
        files.forEach { file ->
            val record = read(file)
            if (record == null) unreadable++ else records += record
        }
        when {
            records.isNotEmpty() -> QuarantineLoad.Present(records.maxBy { it.stagedAtMs })
            unreadable > 0 -> QuarantineLoad.Unreadable
            else -> QuarantineLoad.Absent
        }
    }

    override suspend fun forTarget(targetUid: String): QuarantineScan =
        withContext(Dispatchers.IO) {
            var unreadable = 0
            val records = dir.listFiles().orEmpty().mapNotNull { file ->
                read(file).also { if (it == null) unreadable++ }
            }.filter { it.targetUid == targetUid }
            QuarantineScan(
                records = records.sortedWith(
                    compareBy<GuestQuarantineRecord> { it.importedAtMs != null }
                        .thenByDescending { it.stagedAtMs },
                ),
                unreadable = unreadable,
            )
        }

    override suspend fun markFinalized(
        guestUid: String,
        transitionId: String,
        targetUid: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val record = read(fileFor(guestUid, transitionId))
        when {
            record == null -> true // nothing staged to bind; the switch result stands
            record.targetUid == targetUid -> true
            else -> write(record.copy(targetUid = targetUid))
        }
    }

    override suspend fun deleteFor(guestUid: String, transitionId: String) =
        withContext(Dispatchers.IO) {
            fileFor(guestUid, transitionId).deleteOrWarn(TAG)
            Unit
        }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    override suspend fun sweepExpired(nowMs: Long): Int = withContext(Dispatchers.IO) {
        var removed = 0
        dir.listFiles().orEmpty().forEach { file ->
            val record = read(file)
            if (record != null && nowMs - record.stagedAtMs > QUARANTINE_RETENTION_MS &&
                file.delete()
            ) {
                removed++
            }
        }
        removed
    }

    private fun fileFor(guestUid: String, transitionId: String): File =
        File(dir, guestHash(guestUid) + "-" + digest(transitionId).take(16) + ".bin")

    private fun write(record: GuestQuarantineRecord): Boolean = try {
        dir.mkdirs()
        val key = keys.getOrCreateKey()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val ciphertext = cipher.doFinal(
                GuestQuarantineCodec.encode(record).toByteArray(Charsets.UTF_8),
            )
            val payload = cipher.iv + ciphertext
            val target = fileFor(record.guestUid, record.transitionId)
            val temp = File(dir, target.name + ".tmp")
            temp.writeBytes(payload)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        } finally {
            key.fill(0)
        }
    } catch (e: Exception) {
        Log.w(TAG, "quarantine write failed (${e.javaClass.simpleName})")
        false
    }

    private fun read(file: File): GuestQuarantineRecord? {
        if (!file.isFile) return null
        return try {
            val payload = file.readBytes()
            if (payload.size <= IV_BYTES) return null
            val key = keys.getOrCreateKey()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, payload, 0, IV_BYTES),
                )
                val plaintext = cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
                GuestQuarantineCodec.decode(String(plaintext, Charsets.UTF_8))
            } finally {
                key.fill(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "quarantine record unreadable (${e.javaClass.simpleName})")
            null
        }
    }

    private fun guestHash(guestUid: String): String =
        digest("guest_quarantine-v1 $guestUid").take(32)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "GuestQuarantine"
        const val DIR_NAME = "guest_quarantine"
        const val KEK_ALIAS = "guest_quarantine_kek"
        const val PREFS_NAME = "guest_quarantine"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
