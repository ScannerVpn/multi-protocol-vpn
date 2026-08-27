package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure pre-flight decision logic (see [Preflight]).
 * Guards the two 2026-08-27 user reports: TUN spinning forever without an
 * admin warning, and PROXY_ONLY users pointed at the wrong local port.
 */
class PreflightTest {

    private fun cfg(protocol: String) = VpnConfig(
        id = "t1",
        name = "test",
        serverIp = "1.2.3.4",
        protocol = protocol,
    )

    private val proxyFamily = listOf("vless", "trojan", "shadowsocks", "hysteria2", "wireguard", "amnezia")
    private val osFamily = listOf("ikev2", "openvpn")

    // ---- wantsTunEngine --------------------------------------------------

    @Test
    fun `TUN mode wants the engine`() {
        assertTrue(Preflight.wantsTunEngine(VpnModes.TUN, SplitModes.OFF, emptyList()))
    }

    @Test
    fun `active split tunneling wants the engine even in proxy modes`() {
        assertTrue(Preflight.wantsTunEngine(VpnModes.SYSTEM_PROXY, SplitModes.INCLUDE, listOf("chrome.exe")))
        assertTrue(Preflight.wantsTunEngine(VpnModes.PROXY_ONLY, SplitModes.EXCLUDE, listOf("game.exe")))
    }

    @Test
    fun `plain proxy modes without apps do NOT want the engine`() {
        assertNull(
            Preflight.tunBlockReason(
                cfg("hysteria2"), VpnModes.PROXY_ONLY, SplitModes.OFF, emptyList(),
                windows = true, elevated = false,
            ),
        )
        assertNull(
            Preflight.tunBlockReason(
                cfg("vless"), VpnModes.SYSTEM_PROXY, SplitModes.OFF, emptyList(),
                windows = true, elevated = false,
            ),
        )
    }

    // ---- tunBlockReason gate ----------------------------------------------

    @Test
    fun `non-elevated Windows launch is BLOCKED for every proxy-family protocol in TUN mode`() {
        proxyFamily.forEach { proto ->
            val reason = Preflight.tunBlockReason(
                cfg(proto), VpnModes.TUN, SplitModes.OFF, emptyList(),
                windows = true, elevated = false,
            )
            assertTrue(reason?.contains("administrator") == true, "protocol $proto was not gated: $reason")
        }
    }

    @Test
    fun `elevated process proceeds without any note`() {
        proxyFamily.forEach { proto ->
            assertNull(
                Preflight.tunBlockReason(
                    cfg(proto), VpnModes.TUN, SplitModes.OFF, emptyList(),
                    windows = true, elevated = true,
                ),
            )
        }
    }

    @Test
    fun `non-Windows hosts proceed unchanged`() {
        assertNull(
            Preflight.tunBlockReason(
                cfg("hysteria2"), VpnModes.TUN, SplitModes.OFF, emptyList(),
                windows = false, elevated = false,
            ),
        )
    }

    @Test
    fun `OS-managed tunnels (ikev2 or openvpn) are never gated by this check`() {
        osFamily.forEach { proto ->
            assertNull(
                Preflight.tunBlockReason(
                    cfg(proto), VpnModes.TUN, SplitModes.OFF, emptyList(),
                    windows = true, elevated = false,
                ),
            )
        }
    }

    @Test
    fun `blocked reason mentions both remedies`() {
        val reason = Preflight.tunBlockReason(
            cfg("vless"), VpnModes.TUN, SplitModes.OFF, emptyList(),
            windows = true, elevated = false,
        )!!
        assertTrue(reason.contains("Run as administrator"))
        assertTrue(reason.contains("System proxy"))
    }

    // ---- endpointSummary ---------------------------------------------------

    @Test
    fun `hysteria2 exposes ONE mixed port (HTTP plus SOCKS on base)`() {
        assertEquals(
            "HTTP+SOCKS 127.0.0.1:10808",
            Preflight.endpointSummary("hysteria2", base = 10808, httpPort = 10809),
        )
    }

    @Test
    fun `xray and wireproxy families expose SEPARATE socks and http ports`() {
        assertEquals(
            "SOCKS 127.0.0.1:10808 · HTTP 127.0.0.1:10809",
            Preflight.endpointSummary("vless", base = 10808, httpPort = 10809),
        )
        assertEquals(
            "SOCKS 127.0.0.1:10808 · HTTP 127.0.0.1:10809",
            Preflight.endpointSummary("amnezia", base = 10808, httpPort = 10809),
        )
    }

    @Test
    fun `isElevated never claims elevation off-Windows`() {
        assertEquals(false, Preflight.isElevated(windows = false))
    }
}
