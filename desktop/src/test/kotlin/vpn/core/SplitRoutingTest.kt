package vpn.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the "System proxy + split tunnel = connected but
 * nothing comes through" report (v3.6.10).
 *
 * Pinned contracts:
 *  1. Split tunneling may only be ENABLED in modes where Windows can really
 *     attribute traffic to a process (TUN engine) — never in Proxy-only mode
 *     ([SplitModes.allowedInMode]).
 *  2. The generated sing-box route blocks keep the documented include/exclude
 *     semantics (include => final DIRECT, exclude => final TUNNEL), because
 *     an inverted or empty rule set silently routes everything against the
 *     user's intent.
 *  3. DNS-leak pinning is suppressed for INCLUDE sessions: apps OUTSIDE the
 *     list must behave as if no VPN existed — including their DNS — instead
 *     of having their lookups forced into the tunnel (which blackholed them
 *     whenever the tunnel was down while their browsing stayed direct).
 */
class SplitRoutingTest {

    // ---- 1. policy -------------------------------------------------------

    @Test
    fun `split tunneling is only allowed where per-process routing is real`() {
        assertTrue(SplitModes.allowedInMode(VpnModes.TUN))
        assertTrue(SplitModes.allowedInMode(VpnModes.SYSTEM_PROXY))
        assertFalse(
            SplitModes.allowedInMode(VpnModes.PROXY_ONLY),
            "plain local ports cannot match by process - enabling splits there only lies",
        )
    }

    // ---- 2. route semantics ----------------------------------------------

    private fun socksTun(splitMode: String?, apps: List<String>? = null) =
        SingBox.buildSocksTunJson(
            socksPort = 10808,
            coreProcess = "xray.exe",
            splitMode = splitMode,
            splitApps = apps ?: listOf("telegram.exe", "chrome.exe"),
            dnsLeakProtection = true,
        )

    @Test
    fun `include routes ONLY the listed apps through the tunnel`() {
        val json = socksTun(SplitModes.INCLUDE)
        assertTrue("\"final\": \"direct\"" in json, "INCLUDE promises everyone-else-direct")
        assertTrue("\"outbound\": \"proxy-out\"" in json)
        assertTrue("telegram.exe" in json && "chrome.exe" in json)
        assertTrue("xray.exe" in json, "the core's own traffic must stay direct")
        // ...and the core/probe/DNS/private direct rules come first.
        val rulesIdx = json.indexOf("\"rules\"")
        val includeIdx = json.indexOf("chrome.exe")
        assertTrue(rulesIdx in 0 until includeIdx)
    }

    @Test
    fun `exclude tunnels everything except the listed apps`() {
        val json = socksTun(SplitModes.EXCLUDE)
        assertTrue("\"final\": \"proxy-out\"" in json)
        // excluded apps + cores share ONE direct rule placed before the
        // catch-all tunnel final.
        val rulesBlock = json.substringAfter("\"rules\"").substringBefore("auto_detect_interface")
        assertTrue("telegram.exe" in rulesBlock && "chrome.exe" in rulesBlock)
        assertTrue(json.contains("\"protocol\": \"dns\", \"outbound\": \"direct\""))
    }

    @Test
    fun `no split keeps the plain full-tunnel route`() {
        val json = SingBox.buildSocksTunJson(
            10808, "xray.exe", splitMode = null, splitApps = null, dnsLeakProtection = true,
        )
        assertTrue("\"final\": \"proxy-out\"" in json)
        assertFalse("telegram.exe" in json)
    }

    @Test
    fun `hy2 tun config follows the same include semantics`() {
        val link = Links.parse(
            "hy2://pw@192.0.2.9:443?insecure=1#t",
        )!!
        val json = SingBox.buildHysteria2Json(
            link, tun = true,
            splitMode = SplitModes.INCLUDE,
            splitApps = listOf("steam.exe"),
            dnsLeakProtection = false,
        )
        assertTrue("\"final\": \"direct\"" in json)
        assertTrue("steam.exe" in json)
        assertTrue("\"hy2-out\"" in json)
    }

    // ---- 3. DNS pin gating -------------------------------------------------

    @Test
    fun `dns leak pin applies when there is no include split`() {
        assertTrue(SingBox.dnsPinActive(true, null))
        assertTrue(SingBox.dnsPinActive(true, SplitModes.OFF))
        assertTrue(SingBox.dnsPinActive(true, SplitModes.EXCLUDE))
    }

    @Test
    fun `include suppresses the dns pin - non-listed apps stay fully direct`() {
        assertFalse(SingBox.dnsPinActive(true, SplitModes.INCLUDE))
        assertFalse(SingBox.dnsPinActive(false, SplitModes.INCLUDE))
    }

    @Test
    fun `generated configs follow the same dns gating`() {
        // "detour" only exists inside the leak-safe DNS block.
        val plain = socksTun(null)
        assertTrue("detour" in plain, "whole-system tunnel keeps the DNS pin")

        val include = socksTun(SplitModes.INCLUDE)
        assertFalse(
            "detour" in include,
            "INCLUDE must not force other apps' DNS through the tunnel",
        )

        val exclude = socksTun(SplitModes.EXCLUDE)
        assertTrue(
            "detour" in exclude,
            "EXCLUDE represents a whole-system tunnel: DNS pinning stays",
        )
    }
}
