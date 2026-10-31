package com.example.myexodus.accountability.rulecontrol

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: the failure path logs via android.util.Log (stub-throws in plain JVM tests).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleDeliveryTraceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val eventId = "0b6f2f4e-1c2d-4e3f-8a9b-1c2d3e4f5a6b"

    @Test
    fun record_appendsOneSingleLinePerStage_withPairAndEventIds() {
        val file = tmp.newFile("rule-delivery.log")
        val trace = RuleDeliveryTrace(file, enabled = true, now = { 1_789_600_000_000L })

        trace.record("pair-1", eventId, "stage=received change=CREATED label=Instagram")
        trace.record("pair-1", eventId, "stage=stored stored=true")

        assertEquals(
            listOf(
                "2026-09-16T23:06:40Z pair=pair-1 event=$eventId " +
                    "stage=received change=CREATED label=Instagram",
                "2026-09-16T23:06:40Z pair=pair-1 event=$eventId stage=stored stored=true",
            ),
            file.readLines(),
        )
    }

    @Test
    fun disabledTrace_writesNothing() {
        val file = tmp.newFile("rule-delivery.log")
        val trace = RuleDeliveryTrace(file, enabled = false)

        trace.record("pair-1", eventId, "stage=received change=DELETED")

        assertEquals(0L, file.length())
    }

    @Test
    fun rotation_keepsTheRunningFileBounded() {
        val file = tmp.newFile("rule-delivery.log")
        val trace = RuleDeliveryTrace(file, enabled = true, maxBytes = 1)

        trace.record("pair-1", eventId, "stage=received")
        trace.record("pair-1", eventId, "stage=stored stored=true")

        assertEquals(1, file.readLines().size)
        assertEquals(1, File(tmp.root, "rule-delivery.log.1").readLines().size)
        assertTrue(file.readText().contains("stage=stored"))
    }
}
