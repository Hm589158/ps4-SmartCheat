package com.ps4smartcheat.app

import com.ps4smartcheat.app.data.CrashReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

class CrashReportsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aCrashIsWrittenLocallyAndShownOnce() {
        val reports = CrashReports(tmp.root, "1.0.12")
        assertNull(reports.last())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
        try {
            reports.install()
            val error = IllegalStateException("boom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertEquals(error, passedOn) // still handed to the system handler (the process ends as usual)
            val report = reports.last()!!
            assertTrue(report, report.startsWith("PS4 SmartCheat 1.0.12 crashed at "))
            assertTrue(report, report.contains("java.lang.IllegalStateException: boom"))
            assertTrue(report, report.contains("CrashReportsTest"))
            // Survives a restart (a new instance reads the same file) until dismissed.
            assertEquals(report, CrashReports(tmp.root, "1.0.12").last())
            reports.dismiss()
            assertNull(CrashReports(tmp.root, "1.0.12").last())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun reportsAreShort() {
        fun deep(n: Int): Nothing = if (n == 0) throw RuntimeException("deep") else deep(n - 1)
        val e = runCatching { deep(500) }.exceptionOrNull()!!
        val r = CrashReports(tmp.root, "x").report("main", e, Date(0))
        assertTrue(r.lines().size <= 125)
    }
}
