package com.example.myexodus.domainusage

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Controllable [MonotonicClock] for the domain-usage unit tests. */
internal class FakeMonotonicClock(@Volatile var now: Long = 0L) : MonotonicClock {
    override fun elapsedMs(): Long = now
}

/** §6.9/S7.3–S7.5: the Android side effects, recorded instead of performed. */
internal class FakeDomainUsageHost(
    @Volatile var override: EngineMode? = null,
    @Volatile var persistedDayKey: String? = null,
) : DomainUsageHost {

    val notifications = AtomicInteger()
    val monitorRegistrations = AtomicInteger()
    val monitorUnregistrations = AtomicInteger()

    @Volatile
    var strictCallback: ((Boolean) -> Unit)? = null

    @Volatile
    var lastDayKeyWritten: String? = null

    override suspend fun modeOverride(): EngineMode? = override

    override suspend fun lastDayKey(): String? = persistedDayKey

    override suspend fun setLastDayKey(dayKey: String) {
        lastDayKeyWritten = dayKey
        persistedDayKey = dayKey
    }

    override fun postVpnStoppedNotification() {
        notifications.incrementAndGet()
    }

    override fun registerPrivateDnsMonitor(onStrictChanged: (Boolean) -> Unit): () -> Unit {
        monitorRegistrations.incrementAndGet()
        strictCallback = onStrictChanged
        return { monitorUnregistrations.incrementAndGet() }
    }
}

/**
 * In-memory [RuleUsageDao] with the real MAX-on-`active_ms` semantics, plus fault switches for
 * the persistence-failure policy. Overrides the shared `upsertMax` transaction (the real ones
 * needs a Room connection) and keeps the same observable contract.
 */
internal class FakeRuleUsageDao : RuleUsageDao() {

    private val rows = LinkedHashMap<String, RuleUsageDaily>()
    private fun key(row: RuleUsageDaily) = "${row.ruleId}:${row.revision}:${row.dayKey}"

    @Volatile var failLoad = false
    @Volatile var failUpsert = false
    @Volatile var failDelete = false
    val loadCalls = AtomicInteger()
    val upsertCalls = AtomicInteger()
    val deletedCutoffs = mutableListOf<String>()

    override suspend fun loadDay(dayKey: String): List<RuleUsageDaily> {
        loadCalls.incrementAndGet()
        if (failLoad) throw IllegalStateException("db down")
        return rows.values.filter { it.dayKey == dayKey }
    }

    override suspend fun maxRevisions(): List<RuleRevision> {
        if (failLoad) throw IllegalStateException("db down")
        return rows.values.groupBy { it.ruleId }
            .map { (id, group) -> RuleRevision(id, group.maxOf { it.revision }) }
    }

    override suspend fun deleteOlderThan(cutoffDayKey: String) {
        if (failDelete) throw IllegalStateException("db down")
        deletedCutoffs += cutoffDayKey
        rows.values.removeAll { it.dayKey < cutoffDayKey }
    }

    override suspend fun upsertMaxRow(ruleId: String, revision: Long, dayKey: String, activeMs: Long) {
        val existing = rows["$ruleId:$revision:$dayKey"]
        if (existing == null || activeMs > existing.activeMs) {
            rows["$ruleId:$revision:$dayKey"] = RuleUsageDaily(ruleId, revision, dayKey, activeMs)
        }
    }

    override suspend fun upsertMax(snapshots: List<UsageSnapshot>) {
        upsertCalls.incrementAndGet()
        if (failUpsert) throw IllegalStateException("db down")
        for (row in snapshots) {
            upsertMaxRow(row.ruleId, row.revision, row.dayKey, row.totalMs)
        }
    }

    fun stored(ruleId: String, revision: Long, dayKey: String): Long? =
        rows["$ruleId:$revision:$dayKey"]?.activeMs

    fun seed(ruleId: String, revision: Long, dayKey: String, activeMs: Long) {
        rows["$ruleId:$revision:$dayKey"] = RuleUsageDaily(ruleId, revision, dayKey, activeMs)
    }
}

/**
 * Controllable [WallClock] for the domain-usage unit tests: a real instant rendered in whatever
 * zone the caller asks for, so a test can produce a different local date for the same moment by
 * changing only the zone — exactly the shape the anchored-zone rule (§4.8) needs.
 */
internal class FakeWallClock(
    var instant: Instant = Instant.parse("2026-09-20T12:00:00Z"),
) : WallClock {
    override fun now(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(instant, zone)
}

/**
 * Hand-rolled DNS wire fixtures (independent of the production builder, so the parser is
 * tested against bytes it did not produce).
 */
internal object DnsFixtures {

    class Writer {
        private val out = java.io.ByteArrayOutputStream()

        val size: Int get() = out.size()

        fun u8(v: Int) = out.write(v and 0xFF)

        fun u16(v: Int) {
            u8(v ushr 8)
            u8(v)
        }

        fun u32(v: Long) {
            u8((v ushr 24).toInt())
            u8((v ushr 16).toInt())
            u8((v ushr 8).toInt())
            u8(v.toInt())
        }

        fun name(n: String) {
            for (label in n.split('.')) {
                if (label.isEmpty()) continue
                u8(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            u8(0)
        }

        fun bytes(b: ByteArray) = out.write(b, 0, b.size)

        fun patchU16(pos: Int, v: Int) {
            val b = out.toByteArray()
            b[pos] = ((v ushr 8) and 0xFF).toByte()
            b[pos + 1] = (v and 0xFF).toByte()
            out.reset()
            out.write(b, 0, b.size)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    sealed interface Answer {
        val owner: String
        val type: Int
        val ttl: Long

        fun writeRdata(w: Writer)
    }

    class A(override val owner: String, private val ip: ByteArray, override val ttl: Long = 300L) : Answer {
        override val type: Int get() = 1
        override fun writeRdata(w: Writer) = w.bytes(ip)
    }

    class Aaaa(override val owner: String, private val ip: ByteArray, override val ttl: Long = 300L) : Answer {
        override val type: Int get() = 28
        override fun writeRdata(w: Writer) = w.bytes(ip)
    }

    class Cname(override val owner: String, private val target: String, override val ttl: Long = 300L) : Answer {
        override val type: Int get() = 5
        override fun writeRdata(w: Writer) = w.name(target)
    }

    class Raw(
        override val owner: String,
        override val type: Int,
        private val rdata: ByteArray,
        override val ttl: Long = 300L,
    ) : Answer {
        override fun writeRdata(w: Writer) = w.bytes(rdata)
    }

    fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    fun query(name: String, type: Int, id: Int = 0x1234, rd: Boolean = true): ByteArray {
        val w = Writer()
        w.u16(id)
        w.u16(if (rd) 0x0100 else 0)
        w.u16(1)
        w.u16(0)
        w.u16(0)
        w.u16(0)
        w.name(name)
        w.u16(type)
        w.u16(1)
        return w.toByteArray()
    }

    /** Byte length of [query]'s question section (header excluded). */
    fun questionBytes(name: String): Int = name.split('.').sumOf { it.length + 1 } + 1 + 4

    fun response(
        qname: String,
        answers: List<Answer>,
        id: Int = 0x1234,
        rcode: Int = 0,
        questionType: Int = 1,
    ): ByteArray {
        val w = Writer()
        w.u16(id)
        w.u16(0x8180 or rcode)
        w.u16(1)
        w.u16(answers.size)
        w.u16(0)
        w.u16(0)
        w.name(qname)
        w.u16(questionType)
        w.u16(1)
        for (a in answers) {
            w.name(a.owner)
            w.u16(a.type)
            w.u16(1)
            w.u32(a.ttl)
            val lengthPos = w.size
            w.u16(0)
            val start = w.size
            a.writeRdata(w)
            w.patchU16(lengthPos, w.size - start)
        }
        return w.toByteArray()
    }
}
