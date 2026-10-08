package com.vlad.ducknetview.data.work

import androidx.work.NetworkType
import com.vlad.ducknetview.domain.model.AppSettings
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkSchedulerTest {

    @Test
    fun `no baseline means the scan job is not worth scheduling`() {
        assertFalse(WorkScheduler.scanEnabled(AppSettings()))
    }

    @Test
    fun `an empty baseline list means the scan job is not worth scheduling`() {
        assertFalse(WorkScheduler.scanEnabled(AppSettings(baseline = emptyList())))
    }

    @Test
    fun `a saved baseline enables the scan job`() {
        assertTrue(
            WorkScheduler.scanEnabled(
                AppSettings(baseline = listOf("tcp|127.0.0.1:22"), baselineAt = 1_000L)
            )
        )
    }

    @Test
    fun `the scan runs every six hours`() {
        val spec = WorkScheduler.scanRequest().workSpec
        assertEquals(TimeUnit.HOURS.toMillis(WorkScheduler.SCAN_INTERVAL_HOURS), spec.intervalDuration)
        assertEquals(6L, WorkScheduler.SCAN_INTERVAL_HOURS)
    }

    @Test
    fun `the scan waits for a battery that is not low`() {
        assertTrue(WorkScheduler.scanRequest().workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `the scan asks for no network, because it talks to itself`() {
        assertEquals(NetworkType.NOT_REQUIRED, WorkScheduler.scanRequest().workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `the rollup runs every twelve hours`() {
        val spec = WorkScheduler.rollupRequest().workSpec
        assertEquals(TimeUnit.HOURS.toMillis(WorkScheduler.ROLLUP_INTERVAL_HOURS), spec.intervalDuration)
        assertEquals(12L, WorkScheduler.ROLLUP_INTERVAL_HOURS)
    }

    @Test
    fun `the rollup has no constraints at all, it reads a local ledger`() {
        val constraints = WorkScheduler.rollupRequest().workSpec.constraints
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresStorageNotLow())
    }

    @Test
    fun `each request names its own worker`() {
        assertEquals(
            ServiceScanWorker::class.java.name,
            WorkScheduler.scanRequest().workSpec.workerClassName,
        )
        assertEquals(
            UsageRollupWorker::class.java.name,
            WorkScheduler.rollupRequest().workSpec.workerClassName,
        )
    }

    @Test
    fun `the unique names are stable and distinct`() {
        assertEquals("ducknetview.service-scan", WorkScheduler.SERVICE_SCAN_WORK)
        assertEquals("ducknetview.usage-rollup", WorkScheduler.USAGE_ROLLUP_WORK)
        assertNotEquals(WorkScheduler.SERVICE_SCAN_WORK, WorkScheduler.USAGE_ROLLUP_WORK)
    }

    @Test
    fun `a fresh request is built each time so UPDATE never reuses an id`() {
        assertNotEquals(WorkScheduler.scanRequest().id, WorkScheduler.scanRequest().id)
    }
}
