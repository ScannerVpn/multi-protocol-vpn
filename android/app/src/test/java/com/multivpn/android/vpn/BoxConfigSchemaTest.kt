package com.multivpn.android.vpn

import com.multivpn.android.data.Settings
import com.multivpn.android.data.SplitModes
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
import java.io.File

/**
 * Pins the rendered config to the sing-box schema the bundled core ACTUALLY
 * speaks (hiddify-core 4.1.0 embeds sagernet/sing-box v1.13.0 — verified by
 * reading the shipped .so; libbox reports 1.13.1 at runtime).
 *
 * These are not style assertions. Every one corresponds to a way the tunnel
 * failed on a live emulator before the schema was corrected:
 *  - `inet4_address`/`inet6_address` → 1.12 replaced both with one `address`
 *    array, and the 1.11 shape made the core reject the config outright;
 *  - a `dns`-type or `block`-type OUTBOUND no longer exists — DNS is a route
 *    ACTION now, so those outbounds were fatal;
 *  - inbound `sniff` moved to `{"action":"sniff"}` in the route rules.
 *
 * The renderer emits text, so the tests parse it: asserting on substrings
 * would pass on malformed JSON, which is exactly what the core rejects.
 */
class BoxConfigSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val vlessReality =
        "vless://5c8d12e3-e55e-4428-ae49-00a4c2e271e3@1.2.3.4:443" +
            "?security=reality&flow=xtls-rprx-vision&type=tcp" +
            "&pbk=JHHlB-yNuTP2ODe4Ko8Acmu4JbEb6EaQ5P49ImFEu3I&sni=example.com&sid=abcd#R"

    private fun config(
        id: String = "t",
        link: String = vlessReality,
        protocol: String = "vless",
    ) = VpnConfig(id = id, name = id, serverIp = "example.com", protocol = protocol, xrayLink = link)

    private fun render(link: String = vlessReality, settings: Settings = Settings()): JsonObject {
        val out = BoxConfigBuilder.build(config(link = link), settings)
        assertTrue("renderer failed: ${out.exceptionOrNull()?.message}", out.isSuccess)
        return json.parseToJsonElement(out.getOrThrow()).jsonObject
    }

    private fun tun(root: JsonObject): JsonObject =
        root["inbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "tun" }

    private fun outbounds(root: JsonObject) =
        root["outbounds"]!!.jsonArray.map { it.jsonObject }

    // ---------- tun inbound (1.12+ single address array) ----------

    @Test
    fun `tun uses the 1_12 address array and not the removed inet4_inet6 fields`() {
        val t = tun(render())
        assertNull("inet4_address was removed in sing-box 1.12", t["inet4_address"])
        assertNull("inet6_address was removed in sing-box 1.12", t["inet6_address"])
        val addrs = t["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("expected an IPv4 CIDR in address[], got $addrs", addrs.any { it.contains('.') && it.contains('/') })
        assertTrue("expected an IPv6 CIDR in address[], got $addrs", addrs.any { it.contains(':') && it.contains('/') })
    }

    @Test
    fun `tun captures traffic - auto_route on, otherwise the device carries nothing`() {
        assertEquals(true, tun(render())["auto_route"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `inbound carries no sniff key - it became a route action in 1_12`() {
        val t = tun(render())
        assertNull("inbound sniff moved to a route action", t["sniff"])
        assertNull("inbound sniff_override_destination moved to a route action", t["sniff_override_destination"])
    }

    // ---------- outbounds (no dns/block types in 1.12+) ----------

    @Test
    fun `no dns or block outbound - both types were removed`() {
        val types = outbounds(render()).map { it["type"]!!.jsonPrimitive.content }
        assertFalse("the dns outbound type was removed in 1.12", types.contains("dns"))
        assertFalse("the block outbound type was removed in 1.12", types.contains("block"))
        assertTrue("a proxy outbound must exist, got $types", types.contains("vless"))
        assertTrue("a direct outbound must exist, got $types", types.contains("direct"))
    }

    @Test
    fun `route final points at a tag that actually exists`() {
        val root = render()
        val tags = outbounds(root).map { it["tag"]!!.jsonPrimitive.content }
        val final = root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
        assertTrue("route.final=$final has no matching outbound tag in $tags", tags.contains(final))
    }

    // ---------- route actions ----------

    @Test
    fun `dns is hijacked by action, not routed to a dns outbound`() {
        val rules = render()["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsRule = rules.firstOrNull { it["protocol"]?.jsonPrimitive?.content == "dns" }
        assertTrue("a DNS rule must exist", dnsRule != null)
        assertEquals("hijack-dns", dnsRule!!["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sniffing is a route action`() {
        val rules = render()["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            "expected a {action: sniff} rule",
            rules.any { it["action"]?.jsonPrimitive?.content == "sniff" },
        )
    }

    @Test
    fun `private destinations bypass the tunnel`() {
        val rules = render()["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val priv = rules.firstOrNull { it["ip_is_private"] != null }
        assertTrue("a private-IP rule must exist", priv != null)
        assertEquals("direct", priv!!["outbound"]!!.jsonPrimitive.content)
    }

    // ---------- dns servers (1.12+ typed form) ----------

    @Test
    fun `dns servers use the typed 1_12 form, not an address URL`() {
        val servers = render()["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertTrue("at least two DNS servers expected", servers.size >= 2)
        servers.forEach { s ->
            assertNull("the address: URL form was replaced by type:+server:", s["address"])
            assertTrue("every DNS server needs a type, got $s", s["type"] != null)
        }
        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "remote" }
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `leak protection off uses the device resolver and never detours dns`() {
        val servers = render(settings = Settings(dnsLeakProtection = false))["dns"]!!
            .jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "remote" }
        assertEquals("local", remote["type"]!!.jsonPrimitive.content)
        assertNull("with leak protection off the resolver must not ride the tunnel", remote["detour"])
    }

    @Test
    fun `the chosen dns server reaches the config`() {
        val servers = render(settings = Settings(dnsServer = "9.9.9.9"))["dns"]!!
            .jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val remote = servers.first { it["tag"]!!.jsonPrimitive.content == "remote" }
        assertEquals("9.9.9.9", remote["server"]!!.jsonPrimitive.content)
    }

    // ---------- selector: switching + measuring ----------

    @Test
    fun `every config becomes a selector member so switching needs no reconnect`() {
        val render = BoxConfigBuilder.buildTunnel(
            configs = listOf(config("a"), config("b"), config("c")),
            activeId = "b",
            settings = Settings(),
        )
        val root = json.parseToJsonElement(render.json).jsonObject
        val selector = outbounds(root).first { it["type"]!!.jsonPrimitive.content == "selector" }
        val members = selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("p-a", "p-b", "p-c"), members)
        assertEquals("p-b", selector["default"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b", "c"), render.includedIds)
    }

    @Test
    fun `switching config interrupts existing connections so the exit IP really changes`() {
        val render = BoxConfigBuilder.buildTunnel(listOf(config("a")), "a", Settings())
        val selector = outbounds(json.parseToJsonElement(render.json).jsonObject)
            .first { it["type"]!!.jsonPrimitive.content == "selector" }
        assertEquals(true, selector["interrupt_exist_connections"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an unrenderable config is reported, not silently dropped`() {
        val render = BoxConfigBuilder.buildTunnel(
            configs = listOf(
                config("good"),
                VpnConfig(id = "ovpn", name = "OldVPN", serverIp = "1.1.1.1", protocol = "openvpn"),
            ),
            activeId = "good",
            settings = Settings(),
        )
        assertEquals(listOf("good"), render.includedIds)
        assertEquals(1, render.rejected.size)
        assertEquals("ovpn", render.rejected.first().configId)
        assertTrue(
            "the reason must name the protocol: ${render.rejected.first().reason}",
            render.rejected.first().reason.contains("OpenVPN"),
        )
    }

    @Test
    fun `an active id that did not render falls back to a config that did`() {
        val render = BoxConfigBuilder.buildTunnel(
            configs = listOf(
                VpnConfig(id = "ovpn", name = "OldVPN", serverIp = "1.1.1.1", protocol = "openvpn"),
                config("good"),
            ),
            activeId = "ovpn",
            settings = Settings(),
        )
        val selector = outbounds(json.parseToJsonElement(render.json).jsonObject)
            .first { it["type"]!!.jsonPrimitive.content == "selector" }
        assertEquals("p-good", selector["default"]!!.jsonPrimitive.content)
    }

    // ---------- probe config (measuring while disconnected) ----------

    @Test
    fun `the probe config has NO tun inbound - measuring must not touch traffic`() {
        val render = BoxConfigBuilder.buildProbe(listOf(config("a"), config("b")))
        val root = json.parseToJsonElement(render.json).jsonObject
        assertNull("a probe config must not create a VPN device", root["inbounds"])
    }

    @Test
    fun `the probe config measures with a urltest group against a real 204 endpoint`() {
        val render = BoxConfigBuilder.buildProbe(listOf(config("a")))
        val group = outbounds(json.parseToJsonElement(render.json).jsonObject)
            .first { it["type"]!!.jsonPrimitive.content == "urltest" }
        assertEquals(BoxConfigBuilder.PROBE_URL, group["url"]!!.jsonPrimitive.content)
    }

    // ---------- split tunneling ----------

    @Test
    fun `include mode puts only the chosen packages in the tunnel`() {
        val t = tun(
            render(
                settings = Settings(
                    splitMode = SplitModes.INCLUDE,
                    splitApps = listOf("com.foo.bar", "com.baz"),
                ),
            ),
        )
        val pkgs = t["include_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("com.foo.bar", "com.baz"), pkgs)
        assertNull(t["exclude_package"])
    }

    @Test
    fun `exclude mode keeps the chosen packages out of the tunnel`() {
        val t = tun(
            render(settings = Settings(splitMode = SplitModes.EXCLUDE, splitApps = listOf("com.foo"))),
        )
        assertEquals(listOf("com.foo"), t["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(t["include_package"])
    }

    @Test
    fun `split off writes no package list even when apps are remembered`() {
        val t = tun(
            render(settings = Settings(splitMode = SplitModes.OFF, splitApps = listOf("com.foo"))),
        )
        assertNull(t["include_package"])
        assertNull(t["exclude_package"])
    }

    // ---------- per-protocol rendering ----------

    @Test
    fun `reality carries the public key and short id the handshake needs`() {
        val tls = outbounds(render()).first { it["type"]!!.jsonPrimitive.content == "vless" }["tls"]!!.jsonObject
        val reality = tls["reality"]!!.jsonObject
        assertEquals(true, reality["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("JHHlB-yNuTP2ODe4Ko8Acmu4JbEb6EaQ5P49ImFEu3I", reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("abcd", reality["short_id"]!!.jsonPrimitive.content)
        assertEquals("example.com", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 0x-prefixed short id is normalized - the core only accepts plain hex`() {
        // Observed live: "decode short_id: encoding/hex: invalid byte: U+0078 'x'"
        assertEquals("123abc", BoxConfigBuilder.normalizeShortId("0x123abc"))
        assertEquals("123abc", BoxConfigBuilder.normalizeShortId("123abc"))
        assertEquals("", BoxConfigBuilder.normalizeShortId(null))
    }

    @Test
    fun `websocket transport keeps its path and host header`() {
        val root = render(
            "vless://5c8d12e3-e55e-4428-ae49-00a4c2e271e3@1.2.3.4:443" +
                "?security=tls&type=ws&path=%2Fabc&host=h.example&sni=h.example#W",
        )
        val tr = outbounds(root).first { it["type"]!!.jsonPrimitive.content == "vless" }["transport"]!!.jsonObject
        assertEquals("ws", tr["type"]!!.jsonPrimitive.content)
        assertEquals("/abc", tr["path"]!!.jsonPrimitive.content)
        assertEquals("h.example", tr["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hysteria2 renders password, obfs and the h3 alpn`() {
        val cfg = VpnConfig(
            id = "h", name = "h", serverIp = "1.2.3.4", protocol = "hysteria2",
            xrayLink = "hy2://secretpass@1.2.3.4:8443?sni=x.example&insecure=1&obfs=salamander&obfs-password=op#H",
        )
        val root = json.parseToJsonElement(BoxConfigBuilder.build(cfg).getOrThrow()).jsonObject
        val ob = outbounds(root).first { it["type"]!!.jsonPrimitive.content == "hysteria2" }
        assertEquals("secretpass", ob["password"]!!.jsonPrimitive.content)
        assertEquals("salamander", ob["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("op", ob["obfs"]!!.jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals(true, ob["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("h3"),
            ob["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    // ---------- WireGuard / AmneziaWG endpoints ----------

    @Test
    fun `a wireguard conf renders as an endpoint with its peer`() {
        val conf = tempConf(
            """
            [Interface]
            PrivateKey = cGxhaW5rZXlwbGFpbmtleXBsYWlua2V5cGxhaW5rMD0=
            Address = 10.7.0.2/32
            MTU = 1420

            [Peer]
            PublicKey = cGVlcmtleXBlZXJrZXlwZWVya2V5cGVlcmtleXBlMD0=
            AllowedIPs = 0.0.0.0/0
            Endpoint = wg.example.com:51820
            PersistentKeepalive = 25
            """.trimIndent(),
        )
        val cfg = VpnConfig(
            id = "wg", name = "wg", serverIp = "wg.example.com", protocol = "wireguard",
            tunnelConfPath = conf.absolutePath,
        )
        val root = json.parseToJsonElement(BoxConfigBuilder.build(cfg).getOrThrow()).jsonObject
        val ep = root["endpoints"]!!.jsonArray.map { it.jsonObject }.first()
        assertEquals("wireguard", ep["type"]!!.jsonPrimitive.content)
        assertEquals("p-wg", ep["tag"]!!.jsonPrimitive.content)
        assertEquals(1420, ep["mtu"]!!.jsonPrimitive.content.toInt())
        val peer = ep["peers"]!!.jsonArray.map { it.jsonObject }.first()
        assertEquals("wg.example.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals(51820, peer["port"]!!.jsonPrimitive.content.toInt())
        assertEquals(25, peer["persistent_keepalive_interval"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an AmneziaWG conf renders as an awg endpoint carrying its obfuscation params`() {
        val conf = tempConf(
            """
            [Interface]
            PrivateKey = cGxhaW5rZXlwbGFpbmtleXBsYWlua2V5cGxhaW5rMD0=
            Address = 10.8.0.5/32
            Jc = 4
            Jmin = 40
            S1 = 30
            H1 = 1000000000-1100000000

            [Peer]
            PublicKey = cGVlcmtleXBlZXJrZXlwZWVya2V5cGVlcmtleXBlMD0=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 203.0.113.9:443
            """.trimIndent(),
        )
        val cfg = VpnConfig(
            id = "awg", name = "awg", serverIp = "203.0.113.9", protocol = "amnezia",
            tunnelConfPath = conf.absolutePath,
        )
        val root = json.parseToJsonElement(BoxConfigBuilder.build(cfg).getOrThrow()).jsonObject
        val ep = root["endpoints"]!!.jsonArray.map { it.jsonObject }.first()
        assertEquals("awg", ep["type"]!!.jsonPrimitive.content)
        assertEquals(4, ep["jc"]!!.jsonPrimitive.content.toInt())
        assertEquals(30, ep["s1"]!!.jsonPrimitive.content.toInt())
        // The RANGE must survive as a string; truncating it breaks the handshake.
        assertEquals("1000000000-1100000000", ep["h1"]!!.jsonPrimitive.content)
    }

    @Test
    fun `awg numeric params are numbers and ranges stay strings`() {
        val pairs = BoxConfigBuilder.awgJsonPairs(
            mapOf("Jc" to "4", "H1" to "1-5", "H2" to "77", "I1" to "<b 0x01>"),
        ).toMap()
        assertEquals("4", pairs["jc"])
        assertEquals("\"1-5\"", pairs["h1"])
        assertEquals("77", pairs["h2"])
        assertEquals("\"<b 0x01>\"", pairs["i1"])
    }

    // ---------- honest refusals ----------

    @Test
    fun `openvpn and ikev2 are refused with a reason naming the protocol`() {
        listOf("openvpn" to "OpenVPN", "ikev2" to "IKEv2").forEach { (proto, label) ->
            val res = BoxConfigBuilder.build(
                VpnConfig(id = proto, name = proto, serverIp = "1.1.1.1", protocol = proto),
            )
            assertTrue("$proto must be refused", res.isFailure)
            assertTrue(
                "the message must name $label: ${res.exceptionOrNull()?.message}",
                res.exceptionOrNull()?.message?.contains(label) == true,
            )
        }
    }

    @Test
    fun `a wireguard config with no conf file is refused, not rendered empty`() {
        val res = BoxConfigBuilder.build(
            VpnConfig(id = "w", name = "w", serverIp = "1.2.3.4", protocol = "wireguard"),
        )
        assertTrue("a WireGuard config without its file must fail loudly", res.isFailure)
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
        val ob = outbounds(root).first { it["type"]!!.jsonPrimitive.content == "trojan" }
        assertEquals("pa\"ss\\", ob["password"]!!.jsonPrimitive.content)
    }

    private fun tempConf(text: String): File =
        File.createTempFile("mvpn-wg", ".conf").apply { writeText(text); deleteOnExit() }
}
