package dev.shizzi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperAccessTest {
    @Test fun onlyTheInstalledAppCanCallTheHelper() {
        assertTrue(HelperAccess.isAppCaller(10234, 10234))
        listOf(0, 1000, 2000, 10235, -1).forEach { assertFalse(HelperAccess.isAppCaller(it, 10234)) }
        assertFalse(HelperAccess.isAppCaller(2000, 2000))
    }

    @Test fun onlyShellCanPublishTheHelper() {
        assertTrue(HelperAccess.isShellCaller(2000))
        listOf(0, 1000, 10234, -1).forEach { assertFalse(HelperAccess.isShellCaller(it)) }
    }
}
