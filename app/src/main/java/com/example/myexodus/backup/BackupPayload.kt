package com.example.myexodus.backup

import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.account.LogicalSnapshotCodec
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import org.json.JSONException
import org.json.JSONObject

sealed class BackupPayloadException(message: String, cause: Throwable? = null) :
    IOException(message, cause) {
    /** §2.1 item 15 caps were exceeded; nothing is uploaded and the user is told the limit. */
    class TooLarge(message: String) : BackupPayloadException(message)

    /** The payload is not a well-formed snapshot; nothing is restored. */
    class Corrupt(message: String, cause: Throwable? = null) : BackupPayloadException(message, cause)
}

/** The streaming restore target. One call per entity, in payload order. */
interface SnapshotSink {
    fun habit(value: HabitProgress)
    fun streak(value: RecoveryStreak)
    fun milestone(value: MilestoneEvent)
    fun attempt(value: AttemptLog)
    fun session(value: RecoverySession)
    fun coaching(value: CoachingStep)
    fun riskWindow(value: RiskWindow)
    fun policy(value: RecoveryPolicy)
}

/**
 * Account plan §6.4 items 6-7 / §6.6 item 6: the logical-data payload. Records are newline-
 * delimited JSON so serialization, compression, encryption and — on restore — entity decoding
 * all stay bounded: no whole-payload array or string is ever allocated. Only the explicit
 * [LogicalSnapshotCodec] entity allowlist can be emitted, so a newly added database table can
 * never leak into a backup automatically.
 */
object BackupPayload {

    const val VERSION = 1
    const val MAX_UNCOMPRESSED_BYTES = BackupEnvelope.MAX_UNCOMPRESSED_BYTES
    const val MAX_COMPRESSED_BYTES = BackupEnvelope.MAX_COMPRESSED_BYTES
    // ADR #247: measured text-only history compresses 25-33:1, so the plan's 5:1 bound refused
    // every real snapshot; 100:1 keeps the bomb guard with 3x headroom over the worst fixture.
    const val MAX_EXPANSION = 100L

    // shortcut: one record is capped at 16 MiB; a legitimate single row above that needs a v2
    // payload format rather than a silently larger buffer.
    const val MAX_RECORD_BYTES = 16 * 1024 * 1024

    data class Sizes(val uncompressedBytes: Long, val compressedBytes: Long)

    private const val FORMAT = "MYEXODUS-SNAPSHOT"
    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_TYPE = "type"
    private const val KEY_DATA = "data"

    private const val TYPE_HABIT = "habit"
    private const val TYPE_STREAK = "streak"
    private const val TYPE_MILESTONE = "milestone"
    private const val TYPE_ATTEMPT = "attempt"
    private const val TYPE_SESSION = "session"
    private const val TYPE_COACHING = "coaching"
    private const val TYPE_RISK_WINDOW = "riskWindow"
    private const val TYPE_POLICY = "policy"

    /**
     * Serializes and compresses [snapshot] into [out] in one pass, enforcing both §2.1 item 15
     * caps while writing. Throws [BackupPayloadException.TooLarge] once a cap is crossed; the
     * caller deletes the partially written staging file.
     */
    fun write(snapshot: GuestSnapshot, out: OutputStream): Sizes {
        val capped = CappedOutputStream(out, MAX_COMPRESSED_BYTES)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val stream = DeflaterOutputStream(capped, deflater, BUFFER_BYTES)
        var uncompressed = 0L
        fun emit(record: JSONObject) {
            val line = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
            uncompressed += line.size
            if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                throw BackupPayloadException.TooLarge(
                    "snapshot exceeds $MAX_UNCOMPRESSED_BYTES bytes uncompressed",
                )
            }
            stream.write(line)
        }
        try {
            emit(JSONObject().put(KEY_FORMAT, FORMAT).put(KEY_VERSION, VERSION))
            snapshot.habits.forEach { emit(record(TYPE_HABIT, LogicalSnapshotCodec.habit(it))) }
            snapshot.streaks.forEach { emit(record(TYPE_STREAK, LogicalSnapshotCodec.streak(it))) }
            snapshot.milestones.forEach { emit(record(TYPE_MILESTONE, LogicalSnapshotCodec.milestone(it))) }
            snapshot.attempts.forEach { emit(record(TYPE_ATTEMPT, LogicalSnapshotCodec.attempt(it))) }
            snapshot.sessions.forEach { emit(record(TYPE_SESSION, LogicalSnapshotCodec.session(it))) }
            snapshot.coaching.forEach { emit(record(TYPE_COACHING, LogicalSnapshotCodec.coaching(it))) }
            snapshot.riskWindows.forEach { emit(record(TYPE_RISK_WINDOW, LogicalSnapshotCodec.riskWindow(it))) }
            snapshot.policy?.let { emit(record(TYPE_POLICY, LogicalSnapshotCodec.policy(it))) }
            stream.finish()
            stream.flush()
        } finally {
            deflater.end()
        }
        val compressed = capped.count
        if (uncompressed > compressed * MAX_EXPANSION) {
            throw BackupPayloadException.TooLarge(
                "snapshot exceeds the $MAX_EXPANSION:1 expansion limit",
            )
        }
        return Sizes(uncompressedBytes = uncompressed, compressedBytes = compressed)
    }

    /**
     * Decompresses exactly [compressedLength] authenticated bytes and emits every record to
     * [sink] in order. The 100 MiB absolute cap and the [MAX_EXPANSION] ratio cap are applied
     * while inflating; the deflate stream must end exactly at the declared length.
     */
    fun read(input: InputStream, compressedLength: Long, sink: SnapshotSink): Sizes {
        if (compressedLength <= 0) throw BackupPayloadException.Corrupt("empty payload")
        if (compressedLength > MAX_COMPRESSED_BYTES) {
            throw BackupPayloadException.TooLarge("payload exceeds $MAX_COMPRESSED_BYTES bytes")
        }
        val maxOutput = minOf(MAX_UNCOMPRESSED_BYTES, compressedLength * MAX_EXPANSION)
        val inflater = Inflater()
        val source = ByteArray(BUFFER_BYTES)
        val inflated = ByteArray(BUFFER_BYTES)
        val lines = LineAssembler()
        var fed = 0L
        var produced = 0L
        var headerSeen = false
        fun consume(line: ByteArray) {
            headerSeen = consumeRecord(line, headerSeen, sink)
        }
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (fed == compressedLength) {
                        throw BackupPayloadException.Corrupt("truncated deflate stream")
                    }
                    val want = minOf(source.size.toLong(), compressedLength - fed).toInt()
                    val n = input.read(source, 0, want)
                    if (n < 0) throw BackupPayloadException.Corrupt("truncated compressed payload")
                    fed += n
                    inflater.setInput(source, 0, n)
                }
                val n = try {
                    inflater.inflate(inflated)
                } catch (e: DataFormatException) {
                    throw BackupPayloadException.Corrupt("corrupt deflate stream", e)
                }
                if (n > 0) {
                    produced += n
                    if (produced > maxOutput) {
                        throw BackupPayloadException.TooLarge(
                            "payload exceeds the $MAX_EXPANSION:1 expansion or " +
                                "$MAX_UNCOMPRESSED_BYTES-byte limit",
                        )
                    }
                    lines.feed(inflated, n, ::consume)
                } else if (inflater.finished()) {
                    break
                } else if (inflater.needsDictionary()) {
                    throw BackupPayloadException.Corrupt("deflate dictionary required")
                } else if (!inflater.needsInput()) {
                    throw BackupPayloadException.Corrupt("stalled deflate stream")
                }
            }
            if (inflater.remaining != 0) {
                throw BackupPayloadException.Corrupt("trailing bytes in compressed payload")
            }
            if (fed != compressedLength) {
                throw BackupPayloadException.Corrupt("trailing bytes in compressed payload")
            }
            if (input.read() != -1) {
                throw BackupPayloadException.Corrupt("trailing bytes after compressed payload")
            }
            lines.finish(::consume)
            if (!headerSeen) throw BackupPayloadException.Corrupt("missing snapshot header")
        } finally {
            inflater.end()
        }
        return Sizes(uncompressedBytes = produced, compressedBytes = compressedLength)
    }

    private fun record(type: String, data: JSONObject) = JSONObject()
        .put(KEY_TYPE, type)
        .put(KEY_DATA, data)

    private fun consumeRecord(line: ByteArray, headerSeen: Boolean, sink: SnapshotSink): Boolean {
        val json = try {
            JSONObject(decodeUtf8(line))
        } catch (e: JSONException) {
            throw BackupPayloadException.Corrupt("malformed record", e)
        }
        if (!headerSeen) {
            if (json.optString(KEY_FORMAT) != FORMAT || json.optInt(KEY_VERSION, -1) != VERSION) {
                throw BackupPayloadException.Corrupt("missing or unsupported snapshot header")
            }
            return true
        }
        val data = json.optJSONObject(KEY_DATA)
            ?: throw BackupPayloadException.Corrupt("record without data")
        try {
            when (json.optString(KEY_TYPE)) {
                TYPE_HABIT -> sink.habit(LogicalSnapshotCodec.habit(data))
                TYPE_STREAK -> sink.streak(LogicalSnapshotCodec.streak(data))
                TYPE_MILESTONE -> sink.milestone(LogicalSnapshotCodec.milestone(data))
                TYPE_ATTEMPT -> sink.attempt(LogicalSnapshotCodec.attempt(data))
                TYPE_SESSION -> sink.session(LogicalSnapshotCodec.session(data))
                TYPE_COACHING -> sink.coaching(LogicalSnapshotCodec.coaching(data))
                TYPE_RISK_WINDOW -> sink.riskWindow(LogicalSnapshotCodec.riskWindow(data))
                TYPE_POLICY -> sink.policy(LogicalSnapshotCodec.policy(data))
                else -> throw BackupPayloadException.Corrupt(
                    "unknown record type '${json.optString(KEY_TYPE)}'",
                )
            }
        } catch (e: JSONException) {
            throw BackupPayloadException.Corrupt("malformed record", e)
        }
        return true
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw BackupPayloadException.Corrupt("record is not valid UTF-8", e)
    }

    private class LineAssembler {
        private val line = ByteArrayOutputStream()

        fun feed(bytes: ByteArray, length: Int, consume: (ByteArray) -> Unit) {
            var start = 0
            while (start < length) {
                var end = start
                while (end < length && bytes[end] != NEWLINE) end++
                val chunk = end - start
                if (line.size() + chunk > BackupPayload.MAX_RECORD_BYTES) {
                    throw BackupPayloadException.Corrupt(
                        "record exceeds ${BackupPayload.MAX_RECORD_BYTES} bytes",
                    )
                }
                line.write(bytes, start, chunk)
                if (end < length) {
                    emit(consume)
                    start = end + 1
                } else {
                    start = length
                }
            }
        }

        fun finish(consume: (ByteArray) -> Unit) {
            if (line.size() > 0) emit(consume)
        }

        private fun emit(consume: (ByteArray) -> Unit) {
            if (line.size() == 0) throw BackupPayloadException.Corrupt("empty record")
            val bytes = line.toByteArray()
            line.reset()
            consume(bytes)
        }
    }

    private class CappedOutputStream(
        private val delegate: OutputStream,
        private val max: Long,
    ) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            ensure(1)
            delegate.write(b)
            count += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            ensure(len.toLong())
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()

        private fun ensure(extra: Long) {
            if (count + extra > max) {
                throw BackupPayloadException.TooLarge("payload exceeds $max compressed bytes")
            }
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
    private const val NEWLINE = '\n'.code.toByte()
}
