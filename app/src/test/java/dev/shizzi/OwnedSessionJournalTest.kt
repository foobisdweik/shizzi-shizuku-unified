package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedSessionJournalTest {
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val nextBoot = "87654321-1234-1234-1234-123456789abc"

    @Test
    fun `a pending start survives serialization before Android replies`() {
        var durable: OwnedSessionRecord? = null
        val started = StartedDownstreams { types -> durable = OwnedSessionRecord(boot, types) }
        started.add(TetheringType.USB)

        val recovered = OwnedSessionRecord.decode(requireNotNull(durable).encode())
        assertEquals(setOf(TetheringType.USB), ownedTypesForRecovery(recovered, boot))
    }

    @Test
    fun `a record from another boot cannot authorize cleanup`() {
        val record = OwnedSessionRecord(boot, setOf(TetheringType.WIFI))
        assertTrue(ownedTypesForRecovery(record, nextBoot).isEmpty())
    }

    @Test
    fun `a cleared record cannot authorize cleanup`() {
        assertTrue(ownedTypesForRecovery(OwnedSessionRecord(boot, emptySet()), boot).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown downstream cannot authorize cleanup`() {
        OwnedSessionRecord.decode("""{"version":1,"boot":"$boot","types":["BLUETOOTH"]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a future journal version cannot authorize cleanup`() {
        OwnedSessionRecord.decode("""{"version":2,"boot":"$boot","types":["WIFI"]}""")
    }

    @Test
    fun `a failed write prevents the start claim from becoming visible`() {
        val started = StartedDownstreams { error("Disk is full") }
        runCatching { started.add(TetheringType.WIFI) }
        assertTrue(started.types.isEmpty())
    }

    @Test
    fun `a failed clear preserves ownership for the next cleanup attempt`() {
        var fail = false
        val started = StartedDownstreams { if (fail) error("Disk is full") }
        started.add(TetheringType.WIFI)
        fail = true
        runCatching { started.clear() }
        assertEquals(setOf(TetheringType.WIFI), started.types)
    }
}
