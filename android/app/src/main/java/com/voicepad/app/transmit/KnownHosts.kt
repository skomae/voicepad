package com.voicepad.app.transmit

/** A computer VoicePad has typed into before. */
data class KnownHost(val address: String, val name: String, val lastUsed: Long)

/**
 * The computers keyboard mode has connected to, most recent first. Pure (no
 * Android types) so it is unit-tested; stored as one tab-separated line per
 * host so no JSON library is needed.
 */
object KnownHosts {
    const val MAX = 10

    /** Adds or refreshes [host], keeping the list most-recent-first and capped at [MAX]. */
    fun upsert(hosts: List<KnownHost>, host: KnownHost): List<KnownHost> =
        (hosts.filter { it.address != host.address } + host)
            .sortedByDescending { it.lastUsed }
            .take(MAX)

    fun forget(hosts: List<KnownHost>, address: String): List<KnownHost> =
        hosts.filter { it.address != address }

    fun encode(hosts: List<KnownHost>): String =
        hosts.joinToString("\n") { h ->
            val name = h.name.replace('\t', ' ').replace('\n', ' ')
            "${h.address}\t${h.lastUsed}\t$name"
        }

    fun decode(text: String?): List<KnownHost> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3 || parts[0].isBlank()) return@mapNotNull null
            val lastUsed = parts[1].toLongOrNull() ?: return@mapNotNull null
            KnownHost(parts[0], parts[2].ifBlank { parts[0] }, lastUsed)
        }.sortedByDescending { it.lastUsed }.take(MAX).toList()
}
