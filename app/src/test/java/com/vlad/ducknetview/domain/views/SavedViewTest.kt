package com.vlad.ducknetview.domain.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedViewTest {

    private val full = SavedView(
        name = "vpn busiest",
        tab = "conns",
        query = "re:a|b\u001fc\\d",
        highlight = true,
        proto = "TCP",
        ipVersion = "V4",
        state = "ESTABLISHED",
        publicOnly = true,
        userAppsOnly = true,
        network = "wlan0",
        connsSortCol = "tx",
        connsSortDesc = false,
        groupByHost = true,
        showClosed = true,
        totals = true,
        revDns = true,
    )

    @Test
    fun everyFieldRoundTrips() {
        assertEquals(full, SavedView.decode(full.encode()))
    }

    @Test
    fun defaultsRoundTrip() {
        val v = SavedView("plain", "apps")
        assertEquals(v, SavedView.decode(v.encode()))
    }

    @Test
    fun aViewWithNoNameOrTabIsRefused() {
        assertNull(SavedView.decode("tab=conns"))
        assertNull(SavedView.decode("name=x"))
        assertNull(SavedView.decode(""))
    }

    @Test
    fun savingUnderAnExistingNameReplacesIt() {
        val a = SavedView("Mine", "conns")
        val b = SavedView("other", "apps")
        val replaced = SavedView.upsert(listOf(a, b), SavedView("mine", "domains"))
        assertEquals(listOf("mine", "other"), replaced.map { it.name })
        assertEquals("domains", replaced[0].tab)
    }

    @Test
    fun decodeAllDropsGarbage() {
        assertEquals(listOf(full), SavedView.decodeAll(listOf("junk", full.encode())))
    }
}
