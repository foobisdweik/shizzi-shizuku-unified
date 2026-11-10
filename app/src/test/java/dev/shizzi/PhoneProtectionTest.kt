package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneProtectionTest {
    @Test fun normalizesRepeatedAndCombinedUsbFunctions() {
        assertEquals("adb,mtp", parseUnlockedUsbFunctions("device_manager\nscreen_unlocked_functions=MTP\nscreen_unlocked_functions=ADB"))
        assertEquals("adb,mtp", parseUnlockedUsbFunctions("device_manager { screen_unlocked_functions=MTP,ADB }"))
        assertEquals("", parseUnlockedUsbFunctions("device_manager { current_functions=ADB }"))
        assertEquals("", parseUnlockedUsbFunctions("device_manager { screen_unlocked_functions=NONE }"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownUsbFunctions() {
        parseUnlockedUsbFunctions("device_manager { screen_unlocked_functions=UNKNOWN } ")
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnrecognizedUsbDump() {
        parseUnlockedUsbFunctions("permission denied")
    }

    @Test fun drainsOutputLargerThanThePipeCapacity() {
        val text = captureProtectionCommand("sh", "-c", "head -c 200000 /dev/zero | tr '\\000' x")
        assertEquals(200_000, text.length)
    }
}
