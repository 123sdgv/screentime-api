package dev.gray.screentime

import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import org.junit.Assume.assumeTrue
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveDeviceTest {
    @Test fun repeatedDaySurvivesClosingAndReopeningDatabase() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-" + UUID.randomUUID() + ".db"
        fun open() = Room.databaseBuilder(ctx, Archive::class.java, name).build()
        val row = Daily("test.synthetic", "2026-05-02", 24000, 3, "events", "UTC")
        try {
            open().use { db ->
                db.dao().saveDays(listOf(row))
                db.dao().saveDays(listOf(row))
            }
            open().use { db ->
                assertEquals(listOf(row), db.dao().existing("2026-01-01", "2026-12-31"))
                assertTrue(db.dao().priorCoverage("2026-01-01", "2026-12-31").isEmpty())
            }
        } finally { ctx.deleteDatabase(name) }
    }

    @Test fun emulatorRecordsActualSettingsActivityAndKeepsItAfterRevocation() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val name = "system-test-" + UUID.randomUUID() + ".db"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val oldMode = shell("appops get " + ctx.packageName + " GET_USAGE_STATS")
        val restore = Regex("GET_USAGE_STATS: (allow|ignore|deny|foreground|default)").find(oldMode)
            ?.groupValues?.get(1) ?: "default"
        try {
            Room.databaseBuilder(ctx, Archive::class.java, name).build().use { db ->
                val collector = Collector(ctx, db)
                assertEquals(PackageManager.PERMISSION_DENIED, ctx.checkSelfPermission(android.Manifest.permission.INTERNET))
                shell("appops set " + ctx.packageName + " GET_USAGE_STATS ignore")
                assertFalse(collector.allowed())
                collector.refresh()
                assertTrue(db.dao().existing("1970", "9999").isEmpty())
                shell("appops set " + ctx.packageName + " GET_USAGE_STATS allow")
                assertTrue(collector.allowed())
                shell("am start -W -a android.settings.SETTINGS")
                SystemClock.sleep(1200)
                shell("input keyevent KEYCODE_HOME")
                var observed: Daily? = null
                for (attempt in 1..10) {
                    collector.refresh()
                    observed = db.dao().existing("1970", "9999").lastOrNull { it.pkg == "com.android.settings" }
                    if ((observed?.ms ?: 0) > 0 && (observed?.entries ?: 0) > 0) break
                    SystemClock.sleep(500)
                }
                assertTrue("No observed settings duration: " + observed, (observed?.ms ?: 0) > 0)
                assertTrue("No estimated settings entry: " + observed, (observed?.entries ?: 0) > 0)
                collector.refresh()
                assertEquals(observed?.entries, db.dao().existing("1970", "9999")
                    .last { it.pkg == "com.android.settings" }.entries)
                shell("appops set " + ctx.packageName + " GET_USAGE_STATS ignore")
                collector.refresh()
                assertTrue(db.dao().existing("1970", "9999").any { it.pkg == "com.android.settings" })
            }
        } finally {
            shell("appops set " + ctx.packageName + " GET_USAGE_STATS " + restore)
            ctx.deleteDatabase(name)
        }
    }
}
