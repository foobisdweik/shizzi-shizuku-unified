package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedDownstreamsTest {

    @Test
    fun `nothing is stopped before anything started`() {
        assertTrue(StartedDownstreams().types.isEmpty())
    }

    @Test
    fun `holds each downstream once it starts`() {
        val started = StartedDownstreams()

        started.add(TetheringType.WIFI)
        started.add(TetheringType.USB)

        assertEquals(setOf(TetheringType.WIFI, TetheringType.USB), started.types)
    }

    @Test
    fun `a stopped session forgets its downstreams`() {
        val started = StartedDownstreams()
        started.add(TetheringType.USB)

        started.clear()

        assertTrue(started.types.isEmpty())
    }

    @Test
    fun `stopping a fresh instance does not erase ownership from a previous helper`() {
        var durable = setOf(TetheringType.WIFI)
        val fresh = StartedDownstreams { durable = it }

        fresh.clear()

        assertTrue(fresh.types.isEmpty())
        assertEquals(setOf(TetheringType.WIFI), durable)
    }

    @Test
    fun `a session persists its own start and confirmed clear`() {
        val writes = mutableListOf<Set<TetheringType>>()
        val started = StartedDownstreams { writes.add(it) }

        started.add(TetheringType.USB)
        started.clear()
        started.clear()

        assertEquals(listOf(setOf(TetheringType.USB), emptySet<TetheringType>()), writes)
    }
}
