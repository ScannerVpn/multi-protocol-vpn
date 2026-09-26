package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrafficProbeTest {

    @Test
    fun `parses one reply from localized Windows ping output`() {
        val output = """
            Pinging 1.2.3.4 with 32 bytes of data:
            Reply from 1.2.3.4: bytes=32 time=42ms TTL=54
        """.trimIndent()
        assertEquals(42, VpnPing.parseWindowsPingOutput(output))
    }

    @Test
    fun `parses less than one millisecond as one`() {
        assertEquals(
            1,
            VpnPing.parseWindowsPingOutput("Reply from 127.0.0.1: bytes=32 time<1ms TTL=128"),
        )
    }

    @Test
    fun `rejects timeout and unrelated numbers`() {
        assertNull(VpnPing.parseWindowsPingOutput("Request timed out."))
        assertNull(VpnPing.parseWindowsPingOutput("Pinging 192.168.1.20 with 32 bytes of data:"))
    }

    // ------------------------------------------------------------------
    // Proxy protocol — the Aether regression (2026-09-24)
    // ------------------------------------------------------------------

    /**
     * A local proxy is NOT protocol-agnostic: an HTTP CONNECT sent to a
     * SOCKS5-only listener is rejected on the very first byte (0x43 'C'
     * instead of the SOCKS version 0x05), verified live against the Aether
     * core — `curl -x http://127.0.0.1:10819` failed instantly with exit 56
     * while `socks5h://127.0.0.1:10819` returned HTTP 204.
     *
     * `Aether.verifyTraffic` used to probe its SOCKS5 port through
     * [TrafficProbe.latencyThroughProxy], which speaks HTTP — so it could
     * never return true, and `connectAether` tore down a perfectly healthy
     * tunnel with "Aether started but no traffic passed through the tunnel".
     * These pin which protocol each entry point speaks.
     */
    @Test
    fun `http proxy entry points speak HTTP and the socks ones speak SOCKS5`() {
        assertEquals(java.net.Proxy.Type.HTTP, TrafficProbe.localProxyType(socks = false))
        assertEquals(java.net.Proxy.Type.SOCKS, TrafficProbe.localProxyType(socks = true))
    }

    /**
     * Every core except Aether serves its HTTP listener on the SAME port as
     * (or one beside) its SOCKS listener, which is why the HTTP-only probe
     * worked for them and hid this bug. Aether is the odd one out, so the
     * ports must stay distinct — if they ever became equal the probe type
     * would silently start mattering for every protocol at once.
     */
    @Test
    fun `aether serves socks and http on different ports`() {
        assertTrue(
            Aether.SOCKS_PORT != Aether.HTTP_PORT,
            "Aether binds SOCKS5 and HTTP CONNECT on separate listeners",
        )
    }

    /**
     * The connect path must budget for the core's OWN scan deadline before it
     * gives up. Measured from aether v2.0.0's log on 2026-09-24: the balanced
     * WireGuard prober gets 80 s and the balanced MASQUE prober 120 s. A
     * shorter ceiling kills the core mid-scan and reports a blocked network.
     */
    @Test
    fun `cold start budget covers the core's own scan deadlines`() {
        assertTrue(
            Aether.coldStartBudgetMs("balanced", 10) >= 130_000L,
            "balanced must outlast the core's 120 s MASQUE scan deadline + validation",
        )
        assertTrue(
            Aether.coldStartBudgetMs("thorough", 10) > Aether.coldStartBudgetMs("balanced", 10),
            "sweeping modes scan far longer than balanced",
        )
        assertTrue(
            Aether.coldStartBudgetMs("turbo", 10) < Aether.coldStartBudgetMs("balanced", 10),
            "turbo stops at the first candidate",
        )
        // Validation time is part of the budget, and an absurd value is clamped.
        assertEquals(
            Aether.coldStartBudgetMs("balanced", 30) - Aether.coldStartBudgetMs("balanced", 10),
            20_000L,
        )
    }
}
