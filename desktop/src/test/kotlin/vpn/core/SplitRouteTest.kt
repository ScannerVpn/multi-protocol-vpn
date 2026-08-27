package vpn.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Split-tunneling route rules for the TUN engine.
 *
 * These tests assert the real JSON produced by the public builder. They are
 * hand-derived (the exact rule ordering and outbound tags matter): the break
 * each test catches is a rule-ORDERING regression — e.g. the include mode
 * accidentally letting non-selected apps into the tunnel, or the selected
 * apps being shadowed by a broader rule.
 */
class SplitRouteTest {

    private fun build(mode: String?, apps: List<String>?): String =
        SingBox.buildSocksTunJson(10808, "xray.exe", splitMode = mode, splitApps = apps)

    // ---- include mode -------------------------------------------------

    @Test
    fun `include sends only selected apps to the tunnel`() {
        val json = build(SplitModes.INCLUDE, listOf("chrome.exe", "firefox.exe"))

        // The selected apps must be routed to the proxy (tunnel).
        assertContains(json, """{"process_name": ["chrome.exe", "firefox.exe"], "outbound": "proxy-out"}""")
        // The catch-all must be direct — non-selected apps stay un-tunneled.
        assertContains(json, """"final": "direct"""")
    }

    @Test
    fun `include must not route dns or private ips to the tunnel`() {
        val json = build(SplitModes.INCLUDE, listOf("chrome.exe"))

        assertContains(json, """{"protocol": "dns", "outbound": "direct"}""")
        assertContains(json, """{"ip_is_private": true, "outbound": "direct"}""")
    }

    @Test
    fun `include keeps core processes off the tunnel to avoid loops`() {
        val json = build(SplitModes.INCLUDE, listOf("chrome.exe"))

        // Loop guard: our own cores (xray, sing-box, the JVM, the app) must
        // never be routed into the tunnel.
        assertContains(json, """"process_name": ["MultiVPN.exe", "java.exe", "javaw.exe", "xray.exe", "HiddifyCli.exe", "sing-box.exe", "wireproxy.exe"], "outbound": "direct"""")
    }

    // ---- exclude mode -------------------------------------------------

    @Test
    fun `exclude sends everything to the tunnel except selected apps`() {
        val json = build(mode = SplitModes.EXCLUDE, apps = listOf("chrome.exe"))

        assertContains(json, """"final": "proxy-out"""")
        assertContains(json, """{"process_name": ["MultiVPN.exe", "java.exe", "javaw.exe", "xray.exe", "HiddifyCli.exe", "sing-box.exe", "wireproxy.exe", "chrome.exe"], "outbound": "direct"}""")
    }

    // ---- off / empty --------------------------------------------------

    @Test
    fun `off mode builds the plain full-tunnel config`() {
        val json = build(mode = SplitModes.OFF, apps = listOf("chrome.exe"))

        assertContains(json, """"final": "proxy-out"""")
        assertFalse(json.contains("chrome.exe"), "off mode must not reference split apps")
    }

    // ---- TUN inbound format (sing-box >= 1.9) -------------------------

    @Test
    fun `tun inbound uses the current address array format`() {
        val json = build(mode = null, apps = null)

        // sing-box >= 1.9 removed inet4_address: the core panics on the
        // deprecated key with "invalid deprecated note: tun-address-x" and
        // never opens the proxy port ("neither the tunnel nor the local
        // proxy came up").
        assertFalse(json.contains("inet4_address"), "deprecated key would panic the core")
        assertContains(json, """"address": ["172.19.0.1/30"]""")
    }
}