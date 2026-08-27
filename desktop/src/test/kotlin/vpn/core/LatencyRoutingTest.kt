package vpn.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the "fake ping on filtered configs" report (v3.6.9):
 *
 * The user's dead/filtered configs kept showing a plausible millisecond value
 * and never timed out, while connecting was impossible. Root cause: when no
 * userspace core could verify a config BEFORE connecting, the old code fell
 * back to a raw TCP port scan (`sshPort/22/443/1194/500/4500`) and reported
 * the handshake RTT as the latency. On Iranian-style filtered networks a bare
 * SYN/ACK completes on almost any open port — the service itself is killed
 * later (after TLS ClientHello) or at the UDP layer (IKEv2 500/4500) — so
 * DEAD configs were painted green while nothing could connect.
 *
 * Contract pinned here:
 *   - only REAL end-to-end traffic tests may produce a number;
 *   - families without a pre-connect verifier classify as UNVERIFIABLE and
 *     resolve to Skipped/no-pill — a broken row must NEVER ping;
 *   - legacy rows whose stored link no longer parses also land in
 *     UNVERIFIABLE instead of the old port-fishing fallback.
 */
class LatencyRoutingTest {

    private fun cfg(
        protocol: String,
        link: String? = null,
        serverIp: String = "192.0.2.10",
    ) = VpnConfig(id = "x-$protocol", name = "c", serverIp = serverIp, protocol = protocol, xrayLink = link)

    // ---- pure classification --------------------------------------------

    @Test
    fun `parsed tcp-proxy links route to the xray real test`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@192.0.2.1:443" +
            "?encryption=none&security=tls&sni=example.com"
        assertEquals(
            VpnService.LatencyEngine.XRAY,
            VpnService.classifyLatencyEngine(cfg("vless", vless)),
        )
        assertEquals(
            VpnService.LatencyEngine.XRAY,
            VpnService.classifyLatencyEngine(cfg("trojan", "trojan://pw@192.0.2.1:443?security=tls")),
        )
        // ss://aes-256-gcm:pw@192.0.2.1:8388 (userinfo = base64(method:password))
        assertEquals(
            VpnService.LatencyEngine.XRAY,
            VpnService.classifyLatencyEngine(
                cfg("shadowsocks", "ss://YWVzLTI1Ni1nY206cHc@192.0.2.1:8388"),
            ),
        )
    }

    @Test
    fun `unusual transports still take the xray engine`() {
        // xhttp/splithttp etc. PARSE fine; whether our xray build supports the
        // transport is decided by the core at runtime (it fails honestly), not
        // by re-routing into the unreachable fallback.
        val xhttp = "vless://11111111-2222-3333-4444-555555555555@192.0.2.1:443" +
            "?type=xhttp&security=reality&sni=example.com&pbk=x&sid=y#t"
        assertEquals(
            VpnService.LatencyEngine.XRAY,
            VpnService.classifyLatencyEngine(cfg("vless", xhttp)),
        )
    }

    @Test
    fun `hysteria2 always routes to sing-box`() {
        assertEquals(
            VpnService.LatencyEngine.SINGBOX,
            VpnService.classifyLatencyEngine(cfg("hysteria2", "hy2://pw@192.0.2.2:443")),
        )
        assertEquals(
            VpnService.LatencyEngine.SINGBOX,
            VpnService.classifyLatencyEngine(cfg("hysteria2", null)),
        )
    }

    @Test
    fun `wireguard family routes to wireproxy`() {
        assertEquals(
            VpnService.LatencyEngine.WIREPROXY,
            VpnService.classifyLatencyEngine(cfg("wireguard", link = null)),
        )
        assertEquals(
            VpnService.LatencyEngine.WIREPROXY,
            VpnService.classifyLatencyEngine(cfg("amnezia", link = null)),
        )
    }

    @Test
    fun `ikev2 and openvpn are unverifiable before connecting`() {
        // These install OS-level tunnels; there is NO honest pre-connect
        // probe — so they get NONE, certainly not a TCP-scan estimate.
        assertEquals(
            VpnService.LatencyEngine.UNVERIFIABLE,
            VpnService.classifyLatencyEngine(cfg("ikev2", link = null)),
        )
        assertEquals(
            VpnService.LatencyEngine.UNVERIFIABLE,
            VpnService.classifyLatencyEngine(cfg("openvpn", link = null)),
        )
    }

    @Test
    fun `a stored link that no longer parses must NOT fall through to a fake ping`() {
        // Legacy import / damaged row: protocol says vless but the link is
        // unparsable. This EXACT shape used to slip past both real tests into
        // the TCP port scan and come back green on filtered servers.
        assertEquals(
            VpnService.LatencyEngine.UNVERIFIABLE,
            VpnService.classifyLatencyEngine(cfg("vless", "not-a-share-link")),
        )
        assertEquals(
            VpnService.LatencyEngine.UNVERIFIABLE,
            VpnService.classifyLatencyEngine(cfg("trojan", "trojan://onlyuser@no-port")),
        )
    }

    // ---- end-to-end outcome (offline-safe: UNVERIFIABLE touches no socket)

    @Test
    fun `unverifiable family yields Skipped and never a millisecond value`() = runBlocking {
        val ikev2 = cfg("ikev2")
        assertEquals(RealPingResult.Skipped, VpnService.configLatencyResult(ikev2, sshPort = 22))
        assertNull(VpnService.configLatencyMs(ikev2, sshPort = 22))

        val ovpn = cfg("openvpn")
        assertEquals(RealPingResult.Skipped, VpnService.configLatencyResult(ovpn, sshPort = 22))
        assertNull(VpnService.configLatencyMs(ovpn))

        // Broken-link leftovers behave identically — silent, never green.
        val legacy = cfg("vless", "garbage://x")
        assertEquals(RealPingResult.Skipped, VpnService.configLatencyResult(legacy))
        assertNull(VpnService.configLatencyMs(legacy))
    }

    @Test
    fun `blank endpoint stays untestable`() = runBlocking {
        val empty = VpnConfig(id = "e", name = "e", serverIp = "", protocol = "vless", xrayLink = null)
        assertEquals(RealPingResult.Skipped, VpnService.configLatencyResult(empty))
    }
}
