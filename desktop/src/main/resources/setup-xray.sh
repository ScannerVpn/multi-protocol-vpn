#!/bin/bash
# Xray VPN Server Setup Script (VLESS+Reality / Trojan / Shadowsocks)
# Usage: sudo bash setup-xray.sh <server-ip> [vless|trojan|shadowsocks]
#
# Detection (read-only) — when an existing installation is found, NO new
# server is installed; existing inbounds are read and share links for all
# clients are printed as:
#     MULTIVPN-LINK: vless://...   (or trojan:// / ss://)
# Detected sources:
#   - 3x-ui / x-ui panel:   /usr/local/x-ui/bin/config.json (+x-ui.db)
#   - Amnezia docker:       container amnezia-xray (conf read via docker exec)
#   - plain xray:           /usr/local/etc/xray/config.json
# Fresh install uses the official Xray-install script and creates ONE inbound
# of the requested type (VLESS+Reality needs no domain; Trojan needs TLS so
# fresh installs are redirected to VLESS+Reality with a warning).

set -e

SERVER_ADDR="${1:?usage: setup-xray.sh <ip> [vless|trojan|shadowsocks] [scan]}"
VARIANT="${2:-vless}"
# SCAN=1: read-only inventory mode for "grab all configs" — emit links when
# an install exists, print MV-XRAY-ABSENT otherwise, NEVER install anything.
SCAN="${3:-}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[-]${NC} $1"; exit 1; }

[[ $EUID -ne 0 ]] && error "Run as root: sudo bash $0"

# --------------------------------------------------------------- detection
XRAY_CONF=""
DOCKER_XRAY=""
for p in /usr/local/x-ui/bin/config.json /usr/local/etc/xray/config.json /etc/x-ui/xray.json /usr/local/x-ui/config.json; do
    if [ -f "$p" ]; then XRAY_CONF="$p"; break; fi
done
if [ -z "$XRAY_CONF" ] && command -v docker > /dev/null 2>&1; then
    DOCKER_XRAY="$(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -iE 'amnezia.*xray|^xray' | head -1 || true)"
    if [ -n "$DOCKER_XRAY" ]; then
        for p in /etc/amnezia/xray/config.json /etc/xray/config.json /config.json; do
            if docker exec "$DOCKER_XRAY" test -f "$p" 2>/dev/null; then
                XRAY_CONF="docker:$DOCKER_XRAY:$p"
                break
            fi
        done
    fi
fi

read_xray_conf() {
    if [ -n "$DOCKER_XRAY" ] && [ -n "$XRAY_CONF" ]; then
        docker exec "$DOCKER_XRAY" cat "${XRAY_CONF##*:}"
    elif [ -n "$XRAY_CONF" ]; then
        cat "$XRAY_CONF"
    fi
}

# --------------------------------------------------- link emitter (python3)
emit_links_py='
import json, sys, base64, urllib.parse
conf = json.load(sys.stdin)
host = sys.argv[1]

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
                c.get("id", ""), host, port, urllib.parse.urlencode(qq), name))
            found += 1
    elif proto == "trojan":
        for c in (ss.get("clients") or []):
            name = c.get("email") or "trojan-%d" % port
            print("MULTIVPN-LINK: trojan://%s@%s:%d?%s#%s" % (
                c.get("password", ""), host, port, urllib.parse.urlencode(q), name))
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
                print("MULTIVPN-LINK: ss://%s@%s:%d#%s" % (b64, host, port, name))
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
                urllib.parse.urlencode(q2), name))
            found += 1
sys.stderr.write("clients found: %d\n" % found)
'

if [ -n "$XRAY_CONF" ]; then
    info "Existing Xray installation detected ($XRAY_CONF) - reading inbounds (no reinstall)."
    command -v python3 > /dev/null 2>&1 || error "python3 is required to read the existing config."
    read_xray_conf | python3 -c "$emit_links_py" "$SERVER_ADDR" 2>/dev/null
    LINKS="$(read_xray_conf | python3 -c "$emit_links_py" "$SERVER_ADDR" 2>/dev/null | grep -c 'MULTIVPN-LINK:' || true)"
    if [ "${LINKS:-0}" -eq 0 ]; then
        warn "No vless/trojan/shadowsocks inbounds with clients were found in the existing config."
        [ "$SCAN" = "scan" ] && echo "MV-XRAY-EMPTY"
    else
        info "Emitted $LINKS share link(s) for existing clients."
    fi
    info "Done. Existing server untouched."
    exit 0
fi

if [ "$SCAN" = "scan" ]; then
    echo "MV-XRAY-ABSENT"
    exit 0
fi

# ---------------------------------------------------------- fresh install
if [ "$VARIANT" = "trojan" ]; then
    warn "Trojan requires a domain + TLS certificate for a fresh install."
    warn "Installing VLESS+Reality instead (no domain needed, DPI-resistant)."
    VARIANT="vless"
fi

info "Installing Xray core (official installer)..."
command -v curl > /dev/null 2>&1 || apt-get install -y -qq curl > /dev/null 2>&1 || true
bash -c "$(curl -L -s https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install > /dev/null 2>&1 || \
    error "Xray install failed (is github reachable from the server?)"
XRAY_BIN=/usr/local/bin/xray
[ -x "$XRAY_BIN" ] || error "xray binary not found after install"

PORT="$(( (RANDOM % 30000) + 10000 ))"
UUID="$("$XRAY_BIN" uuid)"
PASS="$(head -c 16 /dev/urandom | base64 | tr -d '=+/' | head -c 20)"
mkdir -p /usr/local/etc/xray
umask 077

if [ "$VARIANT" = "vless" ]; then
    KEYOUT="$("$XRAY_BIN" x25519)"
    PRIV="$(echo "$KEYOUT" | grep -oE 'Private key: .*' | cut -d' ' -f3)"
    PBK="$(echo "$KEYOUT" | grep -oE 'Public key: .*' | cut -d' ' -f3)"
    [ -z "$PRIV" ] || [ -z "$PBK" ] && error "x25519 key generation failed"
    SNI="www.microsoft.com"
    cat > /usr/local/etc/xray/config.json << XEOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0",
    "port": $PORT,
    "protocol": "vless",
    "settings": {
      "clients": [{"id": "$UUID", "flow": "xtls-rprx-vision"}],
      "decryption": "none"
    },
    "streamSettings": {
      "network": "tcp",
      "security": "reality",
      "realitySettings": {
        "dest": "$SNI:443",
        "serverNames": ["$SNI"],
        "privateKey": "$PRIV",
        "shortIds": [""]
      }
    }
  }],
  "outbounds": [{"protocol": "freedom"}]
}
XEOF
    systemctl enable xray > /dev/null 2>&1 || true
    systemctl restart xray || error "xray failed to start (check journalctl -u xray)"
    iptables -C INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport "$PORT" -j ACCEPT
    if command -v ufw > /dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "$PORT/tcp" > /dev/null 2>&1 || true
    fi
    echo "MULTIVPN-LINK: vless://$UUID@$SERVER_ADDR:$PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$SNI&fp=chrome&pbk=$PBK&type=tcp&sid=#MultiVPN-VLESS"
    info "VLESS+Reality ready on port $PORT (flow xtls-rprx-vision)."
elif [ "$VARIANT" = "shadowsocks" ]; then
    cat > /usr/local/etc/xray/config.json << XEOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0",
    "port": $PORT,
    "protocol": "shadowsocks",
    "settings": {
      "method": "chacha20-ietf-poly1305",
      "password": "$PASS",
      "network": "tcp,udp"
    }
  }],
  "outbounds": [{"protocol": "freedom"}]
}
XEOF
    systemctl enable xray > /dev/null 2>&1 || true
    systemctl restart xray || error "xray failed to start (check journalctl -u xray)"
    iptables -C INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport "$PORT" -j ACCEPT
    iptables -C INPUT -p udp --dport "$PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport "$PORT" -j ACCEPT
    if command -v ufw > /dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "$PORT" > /dev/null 2>&1 || true
    fi
    B64="$(printf '%s:%s' "chacha20-ietf-poly1305" "$PASS" | base64 -w0)"
    echo "MULTIVPN-LINK: ss://$B64@$SERVER_ADDR:$PORT#MultiVPN-SS"
    info "Shadowsocks ready on port $PORT."
else
    error "Unknown variant: $VARIANT (use vless|trojan|shadowsocks)"
fi

info "========================================="
info "  Xray ($VARIANT) is READY!"
info "========================================="
warn "Open TCP $PORT in your cloud firewall if needed."
