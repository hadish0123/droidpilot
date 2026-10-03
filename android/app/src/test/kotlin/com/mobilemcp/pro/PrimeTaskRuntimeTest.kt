package com.mobilemcp.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeTaskRuntimeTest {

    @Test
    fun taskCodecRoundTripsScheduleAndStatus() {
        val original =
            listOf(
                PrimeTaskRecord(
                    id = "task-1",
                    title = "Morning brief",
                    prompt =
                        "Summarize my project goals",
                    type =
                        PrimeTaskType.AI_BRIEF,
                    scheduleKind =
                        PrimeTaskScheduleKind
                            .PERIODIC,
                    initialDelayMinutes = 5,
                    intervalMinutes = 60,
                    enabled = true,
                    createdAt = 10,
                    updatedAt = 20,
                    lastRunAt = 30,
                    lastStatus = "success"
                )
            )

        assertEquals(
            original,
            PrimeTaskCodec.decode(
                PrimeTaskCodec.encode(
                    original
                )
            )
        )
    }

    @Test
    fun recurringTaskRequiresWorkManagerMinimumInterval() {
        val invalid =
            PrimeTaskRecord(
                id = "task",
                title = "Too fast",
                prompt = "test",
                type =
                    PrimeTaskType.REMINDER,
                scheduleKind =
                    PrimeTaskScheduleKind
                        .PERIODIC,
                initialDelayMinutes = 0,
                intervalMinutes = 5,
                enabled = true,
                createdAt = 1,
                updatedAt = 1
            )

        try {
            PrimeTaskValidation
                .validate(invalid)
            throw AssertionError(
                "Expected interval rejection"
            )
        } catch (
            _: IllegalArgumentException
        ) {
        }
    }

    @Test
    fun oneTimeTaskRejectsInterval() {
        val invalid =
            PrimeTaskRecord(
                id = "task",
                title = "Once",
                prompt = "test",
                type =
                    PrimeTaskType.REMINDER,
                scheduleKind =
                    PrimeTaskScheduleKind
                        .ONE_TIME,
                initialDelayMinutes = 0,
                intervalMinutes = 60,
                enabled = true,
                createdAt = 1,
                updatedAt = 1
            )

        try {
            PrimeTaskValidation
                .validate(invalid)
            throw AssertionError(
                "Expected one-time interval rejection"
            )
        } catch (
            _: IllegalArgumentException
        ) {
        }
    }

    @Test
    fun disabledTaskStillDecodesForManagement() {
        val record =
            PrimeTaskRecord(
                id = "task",
                title = "Disabled",
                prompt = "later",
                type =
                    PrimeTaskType.REMINDER,
                scheduleKind =
                    PrimeTaskScheduleKind
                        .ONE_TIME,
                initialDelayMinutes = 10,
                intervalMinutes = null,
                enabled = false,
                createdAt = 1,
                updatedAt = 1
            )

        val decoded =
            PrimeTaskCodec.decode(
                PrimeTaskCodec.encode(
                    listOf(record)
                )
            ).single()

        assertFalse(decoded.enabled)
        assertTrue(
            decoded.lastRunAt == null
        )
    }
}
