#!/bin/bash
# MultiVPN — export EXISTING configs from a server (READ-ONLY).
# Usage: sudo bash export-existing.sh
#
# Emits for the client app:
#   MULTIVPN-LINK: <share-link>       for every vless/trojan/ss/hy2 client
#                                     (3x-ui panel db AND standalone xray);
#   MULTIVPN-CONF:<name>:<base64>     for WireGuard / AmneziaWG / OpenVPN
#                                     client config files.
# NOTHING is installed, started or modified — this is the «وارد کردن از سرور»
# backend (user request 2026-09-14). The xray link emitter is the SAME
# battle-tested python block setup-xray.sh uses for existing installs.
set -o pipefail

GREEN='\033[0;32m'; NC='\033[0m'
info() { echo -e "${GREEN}[+]${NC} $1"; }

[[ $EUID -ne 0 ]] && { echo "run as root"; exit 1; }

# ---- 1) xray-family links: reuse setup-xray.sh's proven detection+emitter --
XRAY_CONF=""
DOCKER_XRAY=""
for p in /usr/local/x-ui/bin/config.json /usr/local/etc/xray/config.json /etc/x-ui/xray.json /usr/local/x-ui/config.json; do
    if [ -f "$p" ]; then XRAY_CONF="$p"; break; fi
done
if [ -z "$XRAY_CONF" ] && command -v docker > /dev/null 2>&1; then
    DOCKER_XRAY="$(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -iE 'amnezia.*xray|^xray' | head -1 || true)"
    if [ -n "$DOCKER_XRAY" ]; then
        for p in /etc/amnezia/xray/config.json /etc/xray/config.json /config.json; do
            if docker exec "$DOCKER_XRAY" test -f "$p" < /dev/null 2>/dev/null; then
                XRAY_CONF="docker:$DOCKER_XRAY:$p"
                break
            fi
        done
    fi
fi

read_xray_conf() {
    if [ -n "$DOCKER_XRAY" ] && [ -n "$XRAY_CONF" ]; then
        docker exec "$DOCKER_XRAY" cat "${XRAY_CONF##*:}" < /dev/null
    elif [ -n "$XRAY_CONF" ]; then
        cat "$XRAY_CONF"
    fi
}

if [ -n "$XRAY_CONF" ] && command -v python3 > /dev/null 2>&1; then
    read_xray_conf | python3 -c 'import json, sys, base64, urllib.parse
conf = json.load(sys.stdin)
host = sys.argv[1].strip("[]")
if ":" in host:
    host = "[%s]" % host  # bare IPv6 breaks every URI — always bracket

def b64u_decode(s):
    s = s.strip().replace("-", "+").replace("_", "/")
    while len(s) % 4: s += "="
    return base64.b64decode(s)

def b64u_encode(b):
    return base64.b64encode(b).decode().rstrip("=").replace("+", "-").replace("/", "_")

def x25519_pub(priv_bytes):
    # RFC 7748 Montgomery ladder — derive the public key of a private key
    P = 2**255 - 19; A24 = 121665
    k = bytearray(priv_bytes)
    k[0] &= 248; k[31] &= 127; k[31] |= 64
    k = int.from_bytes(bytes(k), "little")
    x1 = 9
    x2, z2, x3, z3, swap = 1, 0, x1, 1, 0
    for t in range(254, -1, -1):
        kt = (k >> t) & 1
        swap ^= kt
        if swap: x2, x3, z2, z3 = x3, x2, z3, z2
        swap = kt
        A = (x2 + z2) % P; AA = A * A % P
        B = (x2 - z2) % P; BB = B * B % P
        E = (AA - BB) % P
        C = (x3 + z3) % P; D = (x3 - z3) % P
        DA = D * A % P; CB = C * B % P
        x3 = pow(DA + CB, 2, P); z3 = x1 * pow(DA - CB, 2, P) % P
        x2 = AA * BB % P; z2 = E * (AA + A24 * E) % P
    if swap: x2, x3, z2, z3 = x3, x2, z3, z2
    return (x2 * pow(z2, P - 2, P) % P).to_bytes(32, "little")

found = 0
for inb in conf.get("inbounds", []):
    proto = inb.get("protocol", "")
    try:
        port = inb.get("port")
        if isinstance(port, str) and "-" in port:
            port = port.split("-")[0]
        port = int(port)
    except Exception:
        continue
    if port <= 0:
        continue
    ss = inb.get("settings", {}) or {}
    st = inb.get("streamSettings", {}) or {}
    net = st.get("network", "tcp")
    sec = st.get("security", "")
    rs = st.get("realitySettings", {}) or {}
    ts = st.get("tlsSettings", {}) or {}
    q = {"type": net}
    if sec == "reality":
        pbk = rs.get("publicKey", "")
        if not pbk and rs.get("privateKey"):
            try:
                pbk = b64u_encode(x25519_pub(b64u_decode(rs["privateKey"])))
            except Exception:
                pbk = ""
        q.update({"security": "reality", "pbk": pbk,
                  "sni": (rs.get("serverNames") or [""])[0], "fp": "chrome",
                  "sid": (rs.get("shortIds") or [""])[0]})
    elif sec == "tls":
        q.update({"security": "tls", "sni": ts.get("serverName", "")})
        if (ts.get("allowInsecure") or ts.get("allowInsecureCertsWithoutNames")):
            q["allowInsecure"] = "1"
    ns = st.get(net + "Settings", {}) or {}
    if net == "ws":
        q["path"] = ns.get("path", "/")
        q["host"] = (ns.get("headers") or {}).get("Host", "")
    elif net == "grpc":
        q["serviceName"] = ns.get("serviceName", "")
    if proto == "vless":
        for c in (ss.get("clients") or []):
            qq = dict(q); qq.setdefault("encryption", "none")
            if sec == "reality":
                qq.setdefault("flow", c.get("flow", "xtls-rprx-vision"))
            name = c.get("email") or "vless-%d" % port
            print("MULTIVPN-LINK: vless://%s@%s:%d?%s#%s" % (
                urllib.parse.quote(c.get("id", ""), safe=""), host, port,
                urllib.parse.urlencode(qq), urllib.parse.quote(name, safe="")))
            found += 1
    elif proto == "trojan":
        for c in (ss.get("clients") or []):
            name = c.get("email") or "trojan-%d" % port
            # A password carrying @ : / %% inside userinfo breaks every parser
            # downstream (including the app itself) — percent-encode it.
            print("MULTIVPN-LINK: trojan://%s@%s:%d?%s#%s" % (
                urllib.parse.quote(c.get("password", ""), safe=""), host, port,
                urllib.parse.urlencode(q), urllib.parse.quote(name, safe="")))
            found += 1
    elif proto == "shadowsocks":
        method = ss.get("method", "")
        server_pw = ss.get("password", "")
        clients = ss.get("clients") or []
        # SS-2022 multi-user: the client password is serverKey:userKey.
        # Legacy ciphers: the inbound password is used directly.
        if method.startswith("2022") and clients:
            for c in clients:
                pw = "%s:%s" % (server_pw, c.get("password", ""))
                b64 = base64.b64encode(("%s:%s" % (method, pw)).encode()).decode()
                name = c.get("email") or "ss-%d" % port
                print("MULTIVPN-LINK: ss://%s@%s:%d#%s" % (b64, host, port, urllib.parse.quote(name, safe="")))
                found += 1
        else:
            pw = server_pw or (clients[0].get("password", "") if clients else "")
            b64 = base64.b64encode(("%s:%s" % (method, pw)).encode()).decode()
            print("MULTIVPN-LINK: ss://%s@%s:%d#ss-%d" % (b64, host, port, port))
            found += 1
    elif proto in ("hysteria", "hysteria2"):
        # x-ui stores hysteria2 as protocol "hysteria" with version 2.
        if proto == "hysteria" and str(ss.get("version", "2")) not in ("2", "v2"):
            continue
        q2 = {}
        sni = ts.get("serverName", "")
        if sni:
            q2["sni"] = sni
        if ts.get("allowInsecure") or not sni:
            q2["insecure"] = "1"
        # Salamander obfuscation lives under streamSettings.finalmask.udp[]
        fm = st.get("finalmask", {}) or {}
        for u in (fm.get("udp") or []):
            if u.get("type") == "salamander":
                opw = ((u.get("settings") or {}).get("password") or "")
                if opw:
                    q2["obfs"] = "salamander"
                    q2["obfs-password"] = opw
        for c in (ss.get("clients") or []):
            auth = c.get("auth") or c.get("password") or ""
            name = c.get("email") or "hy2-%d" % port
            print("MULTIVPN-LINK: hy2://%s@%s:%d?%s#%s" % (
                urllib.parse.quote(auth, safe=""), host, port,
                urllib.parse.urlencode(q2), urllib.parse.quote(name, safe="")))
            found += 1
sys.stderr.write("clients found: %d\n" % found)
' "$(curl -fsS --max-time 8 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')" 2>/dev/null || true
fi

# ---- 2) WireGuard / AmneziaWG conf files (host, then docker) ---------------
emit_conf() { # emit_conf <name> <text>
    printf 'MULTIVPN-CONF:%s:%s\n' "$1" "$(printf '%s' "$2" | base64 -w0)"
}
for f in /etc/wireguard/*.conf; do
    [ -f "$f" ] || continue
    emit_conf "$(basename "$f")" "$(cat "$f")"
done
if command -v docker > /dev/null 2>&1; then
  for name in $(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -iE 'amnezia|wireguard|^a?wg' || true); do
    for p in /opt/amnezia/awg/awg0.conf /opt/amnezia/wireguard/wg0.conf /etc/amnezia/amnezia-wg/wg0.conf /etc/amnezia/wg0.conf /etc/wireguard/wg0.conf; do
      docker exec "$name" test -f "$p" < /dev/null 2>/dev/null || continue
      emit_conf "docker-$name.conf" "$(docker exec "$name" cat "$p" < /dev/null 2>/dev/null || true)"
      break
    done
  done
fi

# ---- 3) OpenVPN client bundle ----------------------------------------------
if [ -d /etc/openvpn ] && ls /etc/openvpn/server* > /dev/null 2>&1; then
  for f in /root/multivpn-openvpn/client.ovpn /root/*.ovpn /etc/openvpn/client/*.ovpn; do
    [ -f "$f" ] || continue
    emit_conf "$(basename "$f")" "$(cat "$f")"
    break
  done
fi

info "export complete"
