package com.peerlink.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTailTest {
    @Test fun matchesTheDisplayAndCrashReportTextAfterWrapsAndReset() {
        val tail = LogTail(11)
        var expected = ""
        repeat(3) {
            for (line in listOf("", "a", "🟢12", "012345", "bc", "01234567890", "a very long line\n", "a", "", "xyz")) {
                expected = (expected + line).takeLast(11)
                tail.append(line)
                assertEquals(expected, tail.toString())
            }
            tail.clear()
            expected = ""
            assertEquals(expected, tail.toString())
        }
    }
}
