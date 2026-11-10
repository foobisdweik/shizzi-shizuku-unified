package dev.shizzi.adb

import org.junit.Assert.*
import org.junit.Test

class AdbMessageTest {
    @Test fun `diagnostics exclude authentication and command payloads`() {
        val secret = "private startup command"
        val frame = AdbMessage(AdbProtocol.A_OPEN, 1, 0, secret)
        assertFalse(frame.toString().contains(secret))
        assertFalse(frame.toString().contains(frame.data!!.contentToString()))
        assertTrue(frame.validate())
    }

    @Test fun `corrupt payload and command are rejected`() {
        val frame = AdbMessage(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(1, 2, 3))
        assertTrue(frame.validate())
        frame.data!![0] = 9
        assertFalse(frame.validate())
        assertFalse(AdbMessage(AdbProtocol.A_WRTE, 1, 2, 0, 0, 0, null).validate())
    }
}
