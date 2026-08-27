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
 *
 * v3.6.11 additions (follow-up report "only Telegram online, every other app
 * offline" in system-proxy + split):
 *  4. Every generated TUN config resolves all of its route references — the
 *     hy2 TUN config used to reference the "direct" outbound WITHOUT ever
 *     declaring it, so sing-box refused to boot and connect silently fell
 *     back to a whole-system proxy with NO split.
 *  5. App picks normalize to the lowercase image names sing-box matches,
 *     so "Telegram"/"CHROME.EXE" can never land outside their own rule.
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

    // ---- 4. route-reference resolution (v3.6.11) -------------------------

    @Test
    fun `every socks-tun config resolves all of its route references`() {
        listOf<String?>(null, SplitModes.INCLUDE, SplitModes.EXCLUDE).forEach { mode ->
            assertEquals(
                null,
                SingBox.unresolvedOutboundRef(socksTun(mode)),
                "mode=$mode must not reference an undeclared outbound",
            )
        }
    }

    @Test
    fun `hy2 tun config declares the direct outbound its split rules need`() {
        val link = Links.parse("hy2://pw@192.0.2.9:443?insecure=1#t")!!
        val json = SingBox.buildHysteria2Json(
            link, tun = true,
            splitMode = SplitModes.INCLUDE,
            splitApps = listOf("steam.exe"),
            dnsLeakProtection = false,
        )
        assertTrue(
            "\"type\": \"direct\", \"tag\": \"direct\"" in json,
            "rules referencing 'direct' are useless without declaring that outbound",
        )
        assertEquals(null, SingBox.unresolvedOutboundRef(json))
        // ...while the plain proxy-mode hy2 config stays unchanged (no direct).
        val plain = SingBox.buildHysteria2Json(link, tun = false)
        assertFalse("\"type\": \"direct\"" in plain)
    }

    // ---- 5. app-name normalization (v3.6.11) -----------------------------

    @Test
    fun `app names normalize to lowercase exe image names`() {
        assertEquals("telegram.exe", SingBox.normalizeAppName("Telegram"))
        assertEquals("chrome.exe", SingBox.normalizeAppName("CHROME.EXE"))
        assertEquals(
            "chrome.exe",
            SingBox.normalizeAppName("C:\\Program Files\\Google\\Chrome\\chrome.EXE"),
        )
        assertEquals("opera.exe", SingBox.normalizeAppName("C:/Apps/opera"))
        assertEquals(null, SingBox.normalizeAppName("   "))
    }

    @Test
    fun `mixed-case picks land in the rules as normalized exe names`() {
        val json = socksTun(SplitModes.INCLUDE, apps = listOf("Telegram", "Chrome"))
        assertTrue("\"telegram.exe\"" in json && "\"chrome.exe\"" in json)
        assertFalse(
            "\"Telegram\"" in json,
            "sing-box rule strings are case-sensitive - raw picks would match nothing",
        )
    }

    // ---- 6. previously-shipped contracts ----------------------------------

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
