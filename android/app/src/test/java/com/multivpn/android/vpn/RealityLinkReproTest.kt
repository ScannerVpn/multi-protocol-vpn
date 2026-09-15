package com.multivpn.android.vpn

import com.multivpn.android.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vpn.core.Links
import vpn.core.VpnConfig

/**
 * Reproduces the user's live Reality link (2026-09-15) end to end:
 * link text -> [Links.parse] -> [BoxConfigBuilder] -> sing-box JSON.
 *
 * This is the exact link reported as "وصل نمیشه" on the Android emulator, so
 * the assertions are the fields a REALITY handshake cannot survive losing:
 * uuid, flow, public_key, short_id and SNI. A regression in any one of them
 * surfaces here instead of as a 20 s "connect not verified" on the device.
 */
class RealityLinkReproTest {

    private val userLink =
        "vless://e4b11ac9-46f7-4cc0-92ed-bb9dfaaf3600@94.237.92.65:443" +
            "?encryption=none&flow=xtls-rprx-vision&fp=chrome" +
            "&pbk=lkMM9FR-o7Z6NwmmQVK8rLhCQR1mbJTgjY_0upeS2SY" +
            "&security=reality&sid=0436301fb0178b&sni=google.com" +
            "&spx=%2F442987454d44398&type=tcp#vless-vless"

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `link parses with every reality field the server emitted`() {
        val l = Links.parse(userLink)!!
        assertEquals("vless", l.protocol)
        assertEquals("94.237.92.65", l.address)
        assertEquals(443, l.port)
        assertEquals("e4b11ac9-46f7-4cc0-92ed-bb9dfaaf3600", l.secret)
        assertEquals("reality", l.params["security"])
        assertEquals("xtls-rprx-vision", l.params["flow"])
        assertEquals("google.com", l.params["sni"])
        assertEquals("chrome", l.params["fp"])
        assertEquals("/442987454d44398", l.params["spx"])
        assertEquals("tcp", l.network)
    }

    @Test
    fun `rendered outbound carries the reality handshake verbatim`() {
        val cfg = VpnConfig(
            id = "u", name = "u", serverIp = "94.237.92.65",
            protocol = "vless", xrayLink = userLink,
        )
        val out = BoxConfigBuilder.build(cfg, Settings())
        assertTrue("renderer failed: ${out.exceptionOrNull()?.message}", out.isSuccess)
        val ob = json.parseToJsonElement(out.getOrThrow()).jsonObject["outbounds"]!!.toString()
        assertTrue("uuid lost: $ob", ob.contains("e4b11ac9-46f7-4cc0-92ed-bb9dfaaf3600"))
        assertTrue("flow lost: $ob", ob.contains("xtls-rprx-vision"))
        assertTrue("pbk lost: $ob", ob.contains("lkMM9FR-o7Z6NwmmQVK8rLhCQR1mbJTgjY_0upeS2SY"))
        assertTrue("sid lost: $ob", ob.contains("0436301fb0178b"))
        assertTrue("sni lost: $ob", ob.contains("google.com"))
        assertTrue("utls fingerprint lost: $ob", ob.contains("chrome"))
        assertTrue("tcp must not emit a transport block: $ob", !ob.contains("\"transport\""))
    }
}