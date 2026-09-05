package com.vlad.ducknetview.engine.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.rates.RateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApiEngineTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the networks flow starts empty rather than null`() {
        val engine = ApiEngine(context, RateTracker())
        assertNotNull(engine.networks.value)
        assertTrue(engine.networks.value.isEmpty())
    }

    @Test
    fun `start then stop is clean`() {
        val engine = ApiEngine(context, RateTracker())
        val scope = CoroutineScope(Job())
        try {
            engine.start(scope)
            engine.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `starting twice does not leave two collectors behind`() {
        val engine = ApiEngine(context, RateTracker())
        val scope = CoroutineScope(Job())
        try {
            engine.start(scope)
            engine.start(scope)
            engine.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stopping without starting is harmless`() {
        ApiEngine(context, RateTracker()).stop()
    }

    @Test
    fun `a sample always produces a snapshot, even with no networks`() = runBlocking {
        val engine = ApiEngine(context, RateTracker())
        val sample = engine.sample(1_000L)
        assertNotNull(sample.networks)
        assertNotNull(sample.total)
        assertNotNull(sample.perUid)
    }

    @Test
    fun `consecutive samples do not throw and keep the flow in sync`() = runBlocking {
        val engine = ApiEngine(context, RateTracker())
        engine.sample(1_000L)
        val second = engine.sample(3_000L)
        assertEquals(second.networks, engine.networks.value)
    }

    @Test
    fun `the rate tracker keys are namespaced per network`() {
        assertEquals("net.100.rx", ApiEngine.rxKey("100"))
        assertEquals("net.100.tx", ApiEngine.txKey("100"))
    }

    @Test
    fun `the catalog and usage sources are wired up`() {
        val engine = ApiEngine(context, RateTracker())
        assertNotNull(engine.appCatalog)
        assertNotNull(engine.usageHistory)
        assertNotNull(engine.deviceRxHistory())
        assertNotNull(engine.deviceTxHistory())
    }
}
