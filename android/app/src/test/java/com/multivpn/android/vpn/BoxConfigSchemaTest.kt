package com.multivpn.android.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vpn.core.VpnConfig

/**
 * Pins the rendered config to the sing-box schema the bundled core ACTUALLY
 * speaks (hiddify-core 4.1.0 embeds sagernet/sing-box v1.13.0 — verified by
 * reading the shipped .so, and libbox reports 1.13.1 at runtime).
 *
 * These are not style assertions. Every one of them corresponds to a way the
 * tunnel failed on a live emulator before the schema was corrected:
 *  - `inet4_address`/`inet6_address` → 1.12 replaced both with one `address`
 *    array; the 1.11 shape made the core reject the config outright;
 *  - a `dns`-type or `block`-type OUTBOUND no longer exists — DNS is a route
 *    ACTION now, so those outbounds were fatal;
 *  - inbound `sniff` moved to `{"action":"sniff"}` in the route rules.
 *
 * The renderer emits text, so the test parses it: asserting on substrings
 * would pass on malformed JSON, which is exactly what the core would reject.
 */
class BoxConfigSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun render(link: String): JsonObject {
        val cfg = VpnConfig(
            id = "t",
            name = "t",
            serverIp = "example.com",
            protocol = "vless",
            xrayLink = link,
        )
        val out = BoxConfigBuilder.build(cfg)
        assertTrue("renderer failed: ${out.exceptionOrNull()?.message}", out.isSuccess)
        return json.parseToJsonElement(out.getOrThrow()).jsonObject
    }

    private val vlessReality =
        "vless://5c8d12e3-e55e-4428-ae49-00a4c2e271e3@1.2.3.4:443" +
            "?security=reality&flow=xtls-rprx-vision&type=tcp" +
            "&pbk=JHHlB-yNuTP2ODe4Ko8Acmu4JbEb6EaQ5P49ImFEu3I&sni=example.com&sid=abcd#R"

    private fun tun(root: JsonObject): JsonObject =
        root["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "tun" }

    // ---------- tun inbound (1.12+ single address array) ----------

    @Test
    fun `tun uses the 1_12 address array and not the removed inet4_inet6 fields`() {
        val t = tun(render(vlessReality))
        assertNull("inet4_address was removed in sing-box 1.12", t["inet4_address"])
        assertNull("inet6_address was removed in sing-box 1.12", t["inet6_address"])
        val addrs = t["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("expected an IPv4 CIDR in address[], got $addrs", addrs.any { it.contains('.') && it.contains('/') })
        assertTrue("expected an IPv6 CIDR in address[], got $addrs", addrs.any { it.contains(':') && it.contains('/') })
    }

    @Test
    fun `tun captures traffic - auto_route on, otherwise the device carries nothing`() {
        val t = tun(render(vlessReality))
        assertEquals(true, t["auto_route"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `inbound carries no sniff key - it became a route action in 1_12`() {
        val t = tun(render(vlessReality))
        assertNull("inbound sniff moved to a route action", t["sniff"])
        assertNull("inbound sniff_override_destination moved to a route action", t["sniff_override_destination"])
    }

    // ---------- outbounds (no dns/block types in 1.12+) ----------

    @Test
    fun `no dns or block outbound - both types were removed`() {
        val types = render(vlessReality)["outbounds"]!!.jsonArray
            .map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertFalse("the dns outbound type was removed in 1.12", types.contains("dns"))
        assertFalse("the block outbound type was removed in 1.12", types.contains("block"))
        assertTrue("a proxy outbound must exist, got $types", types.contains("vless"))
        assertTrue("a direct outbound must exist, got $types", types.contains("direct"))
    }

    @Test
    fun `route final points at the proxy tag that actually exists`() {
        val root = render(vlessReality)
        val tags = root["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        val final = root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
        assertTrue("route.final=$final has no matching outbound tag in $tags", tags.contains(final))
    }

    // ---------- route actions ----------

    @Test
    fun `dns is hijacked by action, not routed to a dns outbound`() {
        val rules = render(vlessReality)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsRule = rules.firstOrNull { it["protocol"]?.jsonPrimitive?.content == "dns" }
        assertTrue("a DNS rule must exist", dnsRule != null)
        assertEquals("hijack-dns", dnsRule!!["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sniffing is a route action`() {
        val rules = render(vlessReality)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            "expected a {action: sniff} rule",
            rules.any { it["action"]?.jsonPrimitive?.content == "sniff" },
        )
    }

    @Test
    fun `private destinations bypass the tunnel`() {
        val rules = render(vlessReality)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val priv = rules.firstOrNull { it["ip_is_private"] != null }
        assertTrue("a private-IP rule must exist", priv != null)
        assertEquals("direct", priv!!["outbound"]!!.jsonPrimitive.content)
    }

    // ---------- dns servers (1.12+ typed form) ----------

    @Test
    fun `dns servers use the typed 1_12 form, not an address URL`() {
        val servers = render(vlessReality)["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertTrue("at least two DNS servers expected", servers.size >= 2)
        servers.forEach { s ->
            assertNull("the address: URL form was replaced by type:+server:", s["address"])
            assertTrue("every DNS server needs a type, got $s", s["type"] != null)
        }
        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "remote" }
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    // ---------- per-protocol rendering ----------

    @Test
    fun `reality carries the public key and short id the handshake needs`() {
        val tls = render(vlessReality)["outbounds"]!!.jsonArray
            .map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "vless" }["tls"]!!.jsonObject
        val reality = tls["reality"]!!.jsonObject
        assertEquals(true, reality["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("JHHlB-yNuTP2ODe4Ko8Acmu4JbEb6EaQ5P49ImFEu3I", reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("abcd", reality["short_id"]!!.jsonPrimitive.content)
        assertEquals("example.com", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `websocket transport keeps its path and host header`() {
        val root = render(
            "vless://5c8d12e3-e55e-4428-ae49-00a4c2e271e3@1.2.3.4:443" +
                "?security=tls&type=ws&path=%2Fabc&host=h.example&sni=h.example#W",
        )
        val tr = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "vless" }["transport"]!!.jsonObject
        assertEquals("ws", tr["type"]!!.jsonPrimitive.content)
        assertEquals("/abc", tr["path"]!!.jsonPrimitive.content)
        assertEquals("h.example", tr["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hysteria2 renders a password and tls block`() {
        val cfg = VpnConfig(
            id = "h", name = "h", serverIp = "1.2.3.4", protocol = "hysteria2",
            xrayLink = "hy2://secretpass@1.2.3.4:8443?sni=x.example&insecure=1#H",
        )
        val root = json.parseToJsonElement(BoxConfigBuilder.build(cfg).getOrThrow()).jsonObject
        val ob = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "hysteria2" }
        assertEquals("secretpass", ob["password"]!!.jsonPrimitive.content)
        assertEquals(true, ob["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content.toBoolean())
    }

    // ---------- honest refusals ----------

    @Test
    fun `a config with no parseable link is refused, not rendered empty`() {
        val cfg = VpnConfig(
            id = "w", name = "w", serverIp = "1.2.3.4", protocol = "wireguard",
            xrayLink = null, tunnelConfPath = "/tmp/x.conf",
        )
        val res = BoxConfigBuilder.build(cfg)
        assertTrue("WireGuard has no builder yet — must fail loudly", res.isFailure)
    }

    @Test
    fun `untrusted link text cannot break out of a JSON string`() {
        // A bare '"' is illegal in a URI, so the quote arrives percent-encoded
        // on the wire; Links decodes it, and the renderer must re-escape it.
        val cfg = VpnConfig(
            id = "q", name = "q", serverIp = "1.2.3.4", protocol = "trojan",
            xrayLink = "trojan://pa%22ss%5C@1.2.3.4:443?security=tls&sni=a.example#Q",
        )
        val out = BoxConfigBuilder.build(cfg)
        assertTrue("renderer failed: ${out.exceptionOrNull()?.message}", out.isSuccess)
        // Parsing is the assertion: an unescaped quote or backslash throws here.
        val root = json.parseToJsonElement(out.getOrThrow()).jsonObject
        val ob = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "trojan" }
        assertEquals("pa\"ss\\", ob["password"]!!.jsonPrimitive.content)
    }
}
