package vpn.core

/**
 * Result row of the read-only server inventory (scan-tunnels.sh).
 * [id] is a protocol identifier understood by AppState.setupServer /
 * grab-all: "wireguard", "amnezia-1.5", "amnezia-2", "amnezia-3",
 * "amnezia-3.1", "openvpn" or "ikev2".
 */
data class TunnelFound(
    val id: String,
    /** Where it lives: "host" or "docker:<container name>". */
    val source: String,
)

object ScanTunnels {

    /** Parses "MV-TUNNEL: <id> [host|docker:<name>]" lines from script output. */
    fun parse(output: String): List<TunnelFound> = output.lineSequence()
        .filter { it.startsWith("MV-TUNNEL:") }
        .map { it.removePrefix("MV-TUNNEL:").trim() }
        .mapNotNull { entry ->
            val parts = entry.split(' ', limit = 2)
            val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TunnelFound(id, parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "host")
        }
        .distinctBy { it.id to it.source }
        .toList()

    /** Extracts "MULTIVPN-LINK: ..." share links from any script output. */
    fun extractLinks(output: String): List<String> = output.lineSequence()
        .filter { it.contains("MULTIVPN-LINK:") }
        .map { it.substringAfter("MULTIVPN-LINK:").trim() }
        .filter { it.contains("://") }
        .distinct()
        .toList()
}
