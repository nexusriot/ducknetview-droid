package com.vlad.ducknetview.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.vlad.ducknetview.ui.components.BarChart
import com.vlad.ducknetview.ui.components.DualSparkline
import com.vlad.ducknetview.ui.components.Sparkline
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class ChartsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun sparklineCount(): Int =
        rule.onAllNodesWithTag("sparkline").fetchSemanticsNodes().size

    private fun dualCount(): Int =
        rule.onAllNodesWithTag("sparkline:dual").fetchSemanticsNodes().size

    private fun barCount(): Int =
        rule.onAllNodesWithTag("barchart").fetchSemanticsNodes().size

    @Test
    fun sparklineRendersForEmptyList() {
        rule.setContent {
            DuckTheme { Sparkline(values = emptyList(), modifier = Modifier.fillMaxWidth()) }
        }
        assertEquals(1, sparklineCount())
    }

    @Test
    fun sparklineRendersForSingleElement() {
        rule.setContent { DuckTheme { Sparkline(values = listOf(42f)) } }
        assertEquals(1, sparklineCount())
    }

    @Test
    fun sparklineRendersForFlatNonZeroSeries() {
        rule.setContent { DuckTheme { Sparkline(values = List(20) { 5f }) } }
        assertEquals(1, sparklineCount())
    }

    @Test
    fun sparklineRendersForAllZeroSeries() {
        rule.setContent { DuckTheme { Sparkline(values = List(8) { 0f }) } }
        assertEquals(1, sparklineCount())
    }

    @Test
    fun sparklineRendersWithoutFill() {
        rule.setContent {
            DuckTheme { Sparkline(values = listOf(1f, 9f, 3f, 7f), fill = false) }
        }
        assertEquals(1, sparklineCount())
    }

    @Test
    fun dualSparklineRendersForTwoEmptySeries() {
        rule.setContent { DuckTheme { DualSparkline(rx = emptyList(), tx = emptyList()) } }
        assertEquals(1, dualCount())
    }

    @Test
    fun dualSparklineRendersForUnevenSeries() {
        rule.setContent {
            DuckTheme { DualSparkline(rx = listOf(3f), tx = listOf(1f, 2f, 3f, 0f)) }
        }
        assertEquals(1, dualCount())
    }

    @Test
    fun barChartRendersForEmptyList() {
        rule.setContent { DuckTheme { BarChart(values = emptyList()) } }
        assertEquals(1, barCount())
    }

    @Test
    fun barChartRendersForSingleElement() {
        rule.setContent { DuckTheme { BarChart(values = listOf(6f)) } }
        assertEquals(1, barCount())
    }

    @Test
    fun barChartRendersForAllZeroSeries() {
        rule.setContent { DuckTheme { BarChart(values = List(30) { 0f }) } }
        assertEquals(1, barCount())
    }

    @Test
    fun barChartRendersForNegativeValues() {
        rule.setContent { DuckTheme { BarChart(values = listOf(-4f, 0f, 3f)) } }
        assertEquals(1, barCount())
    }
}
