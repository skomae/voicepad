package com.voicepad.app.transmit

import org.junit.Assert.assertEquals
import org.junit.Test

class KnownHostsTest {

    private val mac = KnownHost("AA:AA:AA:AA:AA:01", "MacBook", 100)
    private val pc = KnownHost("AA:AA:AA:AA:AA:02", "Work PC", 200)

    @Test
    fun upsertKeepsMostRecentFirstWithoutDuplicates() {
        var hosts = KnownHosts.upsert(emptyList(), mac)
        hosts = KnownHosts.upsert(hosts, pc)
        hosts = KnownHosts.upsert(hosts, mac.copy(name = "MacBook Pro", lastUsed = 300))
        assertEquals(listOf("MacBook Pro", "Work PC"), hosts.map { it.name })
    }

    @Test
    fun upsertIsCapped() {
        var hosts = emptyList<KnownHost>()
        repeat(15) { hosts = KnownHosts.upsert(hosts, KnownHost("addr$it", "h$it", it.toLong())) }
        assertEquals(KnownHosts.MAX, hosts.size)
        assertEquals("h14", hosts.first().name)
    }

    @Test
    fun forgetRemovesOnlyThatHost() {
        assertEquals(listOf(pc), KnownHosts.forget(listOf(mac, pc), mac.address))
    }

    @Test
    fun encodeDecodeRoundTripsAndSanitisesNames() {
        val odd = KnownHost("AA:AA:AA:AA:AA:03", "Tab\tand\nnewline", 50)
        val decoded = KnownHosts.decode(KnownHosts.encode(listOf(mac, pc, odd)))
        assertEquals(listOf(pc, mac, odd.copy(name = "Tab and newline")), decoded)
    }

    @Test
    fun decodeSkipsGarbage() {
        assertEquals(emptyList<KnownHost>(), KnownHosts.decode(null))
        assertEquals(listOf(mac), KnownHosts.decode("junk\n\n${KnownHosts.encode(listOf(mac))}\nx\ty\tz"))
    }
}
