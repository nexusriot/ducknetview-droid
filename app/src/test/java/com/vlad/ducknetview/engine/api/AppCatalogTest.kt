package com.vlad.ducknetview.engine.api

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppCatalogTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * INVALID_UID is the platform declining to name an owner, which it does for
     * every ICMP echo and for any flow whose socket closed before
     * getConnectionOwnerUid was asked. The label used to interpolate the
     * sentinel, so the app column of the connection table, the detail pane and
     * the CSV export all printed a literal "uid -1" as though -1 were a user.
     */
    @Test
    fun `an invalid uid is named unknown rather than printing the sentinel`() {
        val row = AppCatalog(context).row(Process.INVALID_UID)
        assertEquals(Process.INVALID_UID, row.uid)
        assertEquals(AppCatalog.UNKNOWN_OWNER, row.label)
        assertFalse("the -1 sentinel reached the label", row.label.contains("-1"))
        assertEquals("", row.packageName)
    }

    @Test
    fun `an unknown uid gets a placeholder label instead of throwing`() {
        val row = AppCatalog(context).row(987654)
        assertEquals(987654, row.uid)
        assertTrue(row.label.isNotBlank())
    }

    @Test
    fun `the calling uid resolves to something nameable`() {
        val row = AppCatalog(context).row(Process.myUid())
        assertNotNull(row)
        assertTrue(row.label.isNotBlank())
    }

    @Test
    fun `rows are cached so the same uid gives an equal row`() {
        val catalog = AppCatalog(context)
        assertEquals(catalog.row(10123), catalog.row(10123))
    }

    @Test
    fun `invalidate clears the cache without breaking lookups`() {
        val catalog = AppCatalog(context)
        val before = catalog.row(10123)
        catalog.invalidate()
        assertEquals(before, catalog.row(10123))
    }

    @Test
    fun `all and userAppUids never throw`() {
        val catalog = AppCatalog(context)
        assertNotNull(catalog.all())
        assertNotNull(catalog.userAppUids())
        assertNotNull(catalog.allUids())
    }

    @Test
    fun `user app uids exclude system uids`() {
        val catalog = AppCatalog(context)
        val system = catalog.all().filter { it.isSystem }.map { it.uid }.toSet()
        assertTrue(catalog.userAppUids().none { it in system })
    }

    @Test
    fun `start and stop are idempotent`() {
        val catalog = AppCatalog(context)
        catalog.start()
        catalog.start()
        catalog.stop()
        catalog.stop()
    }

    @Test
    fun `fallback labels name the well-known uids`() {
        assertEquals("root", AppCatalog.fallbackLabel(AppCatalog.ROOT_UID))
        assertEquals("system", AppCatalog.fallbackLabel(AppCatalog.SYSTEM_UID))
        assertEquals("uid 10123", AppCatalog.fallbackLabel(10123))
    }

    @Test
    fun `a shared uid name loses its shared prefix`() {
        assertEquals(
            "android.uid.system",
            AppCatalog.prettyUidName("shared:android.uid.system"),
        )
        assertEquals("com.example.app", AppCatalog.prettyUidName("com.example.app"))
        assertEquals(null, AppCatalog.prettyUidName(null))
        assertEquals(null, AppCatalog.prettyUidName("   "))
    }

    @Test
    fun `uids below the first application uid belong to the platform`() {
        assertTrue(AppCatalog.isSystemUid(0))
        assertTrue(AppCatalog.isSystemUid(1000))
        assertTrue(AppCatalog.isSystemUid(9999))
        assertFalse(AppCatalog.isSystemUid(10000))
        assertFalse(AppCatalog.isSystemUid(-1))
    }

    @Test
    fun `both the system flag and the updated-system flag mark a system app`() {
        val plain = ApplicationInfo().apply { flags = 0 }
        val system = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
        val updated = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP }
        assertFalse(AppCatalog.isSystemApp(plain))
        assertTrue(AppCatalog.isSystemApp(system))
        assertTrue(AppCatalog.isSystemApp(updated))
    }
}
