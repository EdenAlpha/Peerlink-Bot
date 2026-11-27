package com.peerlink.app.godmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeRamReportTest {
    private val header = """
        MemTotal:        3833656 kB
        MemAvailable:    1164288 kB
        Total PSS by process:
    """.trimIndent() + "\n"

    @Test fun subtractsProportionalSwapAndRanksByMemoryStillResident() {
        val raw = header + """
            531,456K: chat (pid 19857 / activities)       (401,408K in swap)
            421,888K: system (pid 1691)                  (100,352K in swap)

            Used RAM: 3,000,000K (2,200,256K used pss + 799,744K kernel)
            ZRAM: 421,888K physical used for 1,800,000K in swap (2,875,236K total swap)
        """.trimIndent()
        val report = PrimeRamReport.format(raw)
        assertTrue(report.contains("Available  1137 of 3744 MiB"))
        assertTrue(report.contains("In use or reserved  2607 MiB"))
        assertTrue(report.contains("Kernel  781 MiB"))
        assertTrue(report.contains("Compressed swap in RAM  412 MiB"))
        assertTrue(report.contains("estimated resident RAM\n314 MiB  system\n127 MiB  chat"))
    }

    @Test fun labelsProcessTotalsWhenAndroidOmitsSwapAndAccountsForTheRest() {
        val raw = buildString {
            append(header)
            for (i in 1..17) appendLine("  ${1024 * i}K: process$i (pid $i)")
            appendLine()
            appendLine("  4,000K: Unrelated category (pid 999)")
            appendLine("Used RAM: 2,000,000K (1,200,256K used pss + 799,744K kernel)")
        }
        val report = PrimeRamReport.format(raw)
        assertTrue(report.contains("may include swap)\n17 MiB  process17"))
        assertTrue(report.contains("3 MiB  Other processes combined"))
        assertFalse(report.contains("Unrelated category"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteServiceOutputIsNeverShownAsACompleteReading() {
        PrimeRamReport.format(header + "2,048K: core (pid 10)\nDUMP TIMEOUT")
    }
}
