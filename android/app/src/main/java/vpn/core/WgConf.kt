package vpn.core

/**
 * wg-quick / AmneziaWG `.conf` parser.
 *
 * DIVERGENCE FROM DESKTOP: the desktop rewrites a conf into wireproxy's own
 * format (`WireProxy.buildConfig`) because it runs amneziawg-go as a separate
 * process. Android has no such process — libbox embeds amneziawg-go directly —
 * so the conf is parsed into this neutral model and rendered as a sing-box
 * endpoint instead. The PARSING rules are the ones the desktop learned the
 * hard way and are repeated here on purpose:
 *
 *  - H1..H4 keep the server's full `min-max` ranges. Truncating them to the
 *    first number silently breaks the AmneziaWG handshake (a real bug once
 *    shipped in setup-wireguard.sh).
 *  - Jc/Jmin/Jmax/S1..S4 and I1..I5 are copied VERBATIM; they must match the
 *    server bit for bit or the peer never answers.
 *  - A missing required key returns null rather than a half-built tunnel that
 *    fails later with an unrelated error.
 */
object WgConf {

    /** One `[Peer]` section. */
    data class Peer(
        val publicKey: String,
        val host: String,
        val port: Int,
        val presharedKey: String? = null,
        val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
        val keepalive: Int? = null,
    )

    /** A parsed conf: the `[Interface]` plus its peer. */
    data class Profile(
        val privateKey: String,
        /** Local tunnel addresses, CIDR form, e.g. 10.0.0.2/32. */
        val addresses: List<String>,
        val peer: Peer,
        val dns: List<String> = emptyList(),
        val mtu: Int? = null,
        /** AmneziaWG obfuscation params, verbatim; empty for plain WireGuard. */
        val awg: Map<String, String> = emptyMap(),
        /** [Awg.V15]/[Awg.V20]/[Awg.V30]/[Awg.V31], or null for plain WireGuard. */
        val awgVersion: String? = null,
    ) {
        val isAmnezia: Boolean get() = awg.isNotEmpty()
    }

    /** @return the parsed profile, or null when a required key is missing. */
    fun parse(text: String): Profile? {
        val privateKey = field(text, "PrivateKey") ?: return null
        val addressRaw = field(text, "Address") ?: return null
        val publicKey = field(text, "PublicKey") ?: return null
        val endpoint = field(text, "Endpoint") ?: return null

        val (host, port) = splitEndpoint(endpoint) ?: return null
        val addresses = addressRaw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeCidr(it) }
        if (addresses.isEmpty()) return null

        val allowed = field(text, "AllowedIPs")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("0.0.0.0/0", "::/0")

        val awg = Awg.ALL_KEYS.mapNotNull { key ->
            field(text, key)?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()

        return Profile(
            privateKey = privateKey,
            addresses = addresses,
            peer = Peer(
                publicKey = publicKey,
                host = host,
                port = port,
                presharedKey = field(text, "PresharedKey")?.takeIf { it.isNotBlank() },
                allowedIps = allowed,
                keepalive = field(text, "PersistentKeepalive")?.trim()?.toIntOrNull(),
            ),
            dns = field(text, "DNS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList(),
            mtu = field(text, "MTU")?.trim()?.toIntOrNull(),
            awg = awg,
            awgVersion = Awg.detectVersion(text),
        )
    }

    /** First value of `Name = value` in a wg-quick style conf. */
    private fun field(text: String, name: String): String? =
        Regex("(?im)^\\s*$name\\s*=\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)

    /**
     * Splits `Endpoint = host:port`, understanding a bracketed IPv6 literal.
     * A bare `lastIndexOf(':')` mangles `[2001:db8::1]:51820` — the same bug
     * the desktop's Links parser had to fix.
     */
    internal fun splitEndpoint(raw: String): Pair<String, Int>? {
        val s = raw.trim()
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close <= 1) return null
            val host = s.substring(1, close)
            val port = s.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            return if (host.isNotBlank() && port in 1..65535) host to port else null
        }
        val idx = s.lastIndexOf(':')
        if (idx <= 0) return null
        val host = s.substring(0, idx)
        val port = s.substring(idx + 1).toIntOrNull() ?: return null
        return if (host.isNotBlank() && port in 1..65535) host to port else null
    }

    /**
     * Ensures a bare address carries a prefix. sing-box's endpoint wants CIDR;
     * many server-generated confs write `Address = 10.0.0.2` with no mask.
     */
    internal fun normalizeCidr(addr: String): String =
        if (addr.contains('/')) addr
        else if (addr.contains(':')) "$addr/128" else "$addr/32"
}
