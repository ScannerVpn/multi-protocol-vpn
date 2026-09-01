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
# A failing pipeline stage used to pass silently (set -e only checks the LAST
# command). Places where an empty result is legitimate end in `|| true`.
set -o pipefail

SERVER_ADDR="${1:?usage: setup-xray.sh <ip> [vless|trojan|shadowsocks] [scan]}"
# Bare IPv6 literals produce unparseable links (vless://u@2001:db8::1:443);
# bracket them once and reuse everywhere links are printed.
LINK_ADDR="$SERVER_ADDR"
case "$LINK_ADDR" in
    \[*\]) ;;
    *\:*) LINK_ADDR="[$LINK_ADDR]" ;;
esac
VARIANT="${2:-vless}"
# SCAN=1: read-only inventory mode for "grab all configs" — emit links when
# an install exists, print MV-XRAY-ABSENT otherwise, NEVER install anything.
SCAN="${3:-}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[-]${NC} $1"; exit 1; }

# The INPUT rules added below are lost on the next reboot unless persisted —
# the port would silently close and the client could no longer connect.
persist_iptables() {
    if command -v netfilter-persistent > /dev/null 2>&1; then
        netfilter-persistent save > /dev/null 2>&1 && {
            info "firewall rules persisted (netfilter-persistent)"; return 0; }
    fi
    if command -v iptables-save > /dev/null 2>&1; then
        mkdir -p /etc/iptables 2>/dev/null || true
        if iptables-save > /etc/iptables/rules.v4 2>/dev/null; then
            info "firewall rules saved to /etc/iptables/rules.v4"
            command -v netfilter-persistent > /dev/null 2>&1 || \
                warn "install iptables-persistent so /etc/iptables/rules.v4 is restored at boot"
            return 0
        fi
    fi
    warn "could NOT persist firewall rules - they will be lost on reboot"
    return 0
}

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

# --------------------------------------------------- link emitter (python3)
emit_links_py='
import json, sys, base64, urllib.parse
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
# The upstream installer is fetched from a PINNED COMMIT and checked against a
# pinned SHA256 before it runs.
#
# `bash -c "$(curl -L -s .../raw/main/install-release.sh)"` — the previous
# version — executes whatever the branch head contains AT THIS MOMENT, as root.
# A single malicious push to XTLS/Xray-install (or a MITM on the redirect) was
# remote root on every server this script ever provisioned, with no audit trail.
# The Xray-install repo publishes NO git tags, so a commit SHA is the only
# immutable reference available; the content hash is belt-and-braces.
#
# TO UPGRADE: review the diff between the pinned commit and the new one, then
# update BOTH constants in the same reviewed commit.
XRAY_INSTALL_COMMIT="e741a4f56d368afbb9e5be3361b40c4552d3710d"
XRAY_INSTALL_SHA256="7f70c95f6b418da8b4f4883343d602964915e28748993870fd554383afdbe555"
XRAY_INSTALL_URL="https://raw.githubusercontent.com/XTLS/Xray-install/${XRAY_INSTALL_COMMIT}/install-release.sh"
XRAY_INSTALLER="$(mktemp /tmp/xray-install.XXXXXX.sh)"
# --proto '=https' --tlsv1.2 refuse a protocol downgrade; -f fails on an HTTP
# error page instead of handing it to bash.
curl -fsSL --proto '=https' --tlsv1.2 --max-time 60 "$XRAY_INSTALL_URL" -o "$XRAY_INSTALLER" || \
    error "could not download the pinned Xray installer (commit ${XRAY_INSTALL_COMMIT:0:12})"
[ -s "$XRAY_INSTALLER" ] || error "downloaded Xray installer is empty"
if command -v sha256sum > /dev/null 2>&1; then
    GOT="$(sha256sum "$XRAY_INSTALLER" | cut -d' ' -f1)"
elif command -v openssl > /dev/null 2>&1; then
    GOT="$(openssl dgst -sha256 -r "$XRAY_INSTALLER" | cut -d' ' -f1)"
else
    GOT=""
    warn "no sha256sum/openssl - cannot verify the installer's hash"
fi
if [ -n "$GOT" ] && [ "$GOT" != "$XRAY_INSTALL_SHA256" ]; then
    rm -f "$XRAY_INSTALLER"
    error "Xray installer SHA256 mismatch (expected $XRAY_INSTALL_SHA256, got $GOT) - refusing to run it as root"
fi
# Structural sanity check for the no-hash-tool case: the real installer defines
# these functions; a captive-portal HTML page or a truncated download does not.
grep -qE '^\s*install_software\s*\(\)' "$XRAY_INSTALLER" || \
    error "downloaded file does not look like the Xray installer - refusing to run it as root"
bash "$XRAY_INSTALLER" @ install > /dev/null 2>&1 || \
    { rm -f "$XRAY_INSTALLER"; error "Xray install failed (is github reachable from the server?)"; }
rm -f "$XRAY_INSTALLER"
XRAY_BIN=/usr/local/bin/xray
[ -x "$XRAY_BIN" ] || error "xray binary not found after install"

# BBR congestion control: measurably faster tunnels on lossy/filtered
# paths. Best-effort - an old kernel without bbr keeps cubic.
if modprobe tcp_bbr 2>/dev/null && sysctl -w net.core.default_qdisc=fq > /dev/null 2>&1 && \
   sysctl -w net.ipv4.tcp_congestion_control=bbr > /dev/null 2>&1; then
    printf 'net.core.default_qdisc = fq\nnet.ipv4.tcp_congestion_control = bbr\n' > /etc/sysctl.d/99-multivpn-xray.conf
    sysctl --system > /dev/null 2>&1 || true
    info "BBR congestion control enabled"
else
    info "BBR unavailable on this kernel - keeping default congestion control"
fi

# A random high port that is NOT already in use. The previous version took the
# first random number, so it could land on an occupied port and xray then
# failed to bind (the script reported success and nothing listened).
pick_free_port() {
    local p tries=0
    while [ "$tries" -lt 40 ]; do
        p="$(( (RANDOM % 30000) + 10000 ))"
        if command -v ss > /dev/null 2>&1; then
            ss -Hltnu "sport = :$p" 2>/dev/null | grep -q . || { echo "$p"; return 0; }
        elif command -v netstat > /dev/null 2>&1; then
            netstat -tuln 2>/dev/null | grep -qE "[:.]$p[[:space:]]" || { echo "$p"; return 0; }
        else
            echo "$p"; return 0
        fi
        tries=$((tries + 1))
    done
    echo "$(( (RANDOM % 30000) + 10000 ))"
}
PORT="$(pick_free_port)"
UUID="$("$XRAY_BIN" uuid)"
# No trailing `head -c` in the pipeline: with `set -o pipefail` a truncating
# head closes the pipe early and a large enough producer dies with SIGPIPE
# (exit 141), which would abort the whole script. Verified: the old 24-byte
# form happens to fit the pipe buffer, but the pattern is a trap — cut the
# string in the shell instead, where nothing can be killed.
PASS_RAW="$(head -c 24 /dev/urandom | base64 | tr -d '=+/
')"
PASS="${PASS_RAW:0:24}"
mkdir -p /usr/local/etc/xray
umask 077

if [ "$VARIANT" = "vless" ]; then
    KEYOUT="$("$XRAY_BIN" x25519)"
    PRIV="$(printf '%s' "$KEYOUT" | grep -oE 'Private key: .*' | cut -d' ' -f3 || true)"
    PBK="$(printf '%s' "$KEYOUT" | grep -oE 'Public key: .*' | cut -d' ' -f3 || true)"
    # `[ -z A ] || [ -z B ] && error` is a precedence trap: when A is non-empty
    # the || short-circuits into the && and error runs anyway on some shells.
    # Test them explicitly.
    if [ -z "$PRIV" ] || [ -z "$PBK" ]; then error "x25519 key generation failed"; fi
    # Reality SNI: a HARDCODED www.microsoft.com made every server this script
    # ever provisioned share one fingerprint — trivially enumerable by a censor
    # ("all traffic claiming microsoft.com on a random high port"). Pick one at
    # random from a set of high-traffic TLS 1.3 hosts, and prefer one that the
    # server can actually reach (Reality proxies the real handshake to it).
    SNI_CANDIDATES="www.microsoft.com www.apple.com www.cloudflare.com www.bing.com dl.google.com www.icloud.com www.samsung.com aws.amazon.com"
    SNI=""
    for cand in $(printf '%s\n' $SNI_CANDIDATES | shuf 2>/dev/null || printf '%s\n' $SNI_CANDIDATES); do
        if command -v curl > /dev/null 2>&1; then
            if curl -sS --max-time 6 --proto '=https' --tlsv1.3 -o /dev/null "https://$cand" 2>/dev/null; then
                SNI="$cand"; break
            fi
        else
            SNI="$cand"; break
        fi
    done
    [ -z "$SNI" ] && SNI="www.microsoft.com"
    # A non-empty shortId is required by some clients and adds another
    # per-server variable; "" alone was another shared fingerprint.
    SHORT_ID="$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    info "Reality front: $SNI (shortId $SHORT_ID)"
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
        "shortIds": ["$SHORT_ID"]
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
    persist_iptables
    echo "MULTIVPN-LINK: vless://$UUID@$LINK_ADDR:$PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$SNI&fp=chrome&pbk=$PBK&type=tcp&sid=$SHORT_ID#MultiVPN-VLESS"
    info "VLESS+Reality ready on port $PORT (flow xtls-rprx-vision)."
elif [ "$VARIANT" = "shadowsocks" ]; then
    # Shadowsocks-2022 (blake3-aes-256-gcm) instead of the legacy AEAD cipher.
    # The legacy chacha20-ietf-poly1305 stream has no replay protection and no
    # per-connection salt binding, which is exactly what active probing
    # exploits — and the README already advertised "Shadowsocks-2022".
    # SS-2022 requires a 32-byte base64 PSK, not a passphrase.
    SS_METHOD="2022-blake3-aes-256-gcm"
    SS_PSK="$(head -c 32 /dev/urandom | base64 -w0)"
    cat > /usr/local/etc/xray/config.json << XEOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0",
    "port": $PORT,
    "protocol": "shadowsocks",
    "settings": {
      "method": "$SS_METHOD",
      "password": "$SS_PSK",
      "network": "tcp,udp"
    }
  }],
  "outbounds": [{"protocol": "freedom"}]
}
XEOF
    systemctl enable xray > /dev/null 2>&1 || true
    # An xray build too old for SS-2022 refuses to start. Fall back to the
    # legacy AEAD cipher rather than leaving the server dead, and say so.
    if ! systemctl restart xray 2>/dev/null || ! systemctl is-active --quiet xray; then
        warn "xray rejected $SS_METHOD (build too old?) - falling back to chacha20-ietf-poly1305"
        SS_METHOD="chacha20-ietf-poly1305"
        SS_PSK="$PASS"
        cat > /usr/local/etc/xray/config.json << XEOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0",
    "port": $PORT,
    "protocol": "shadowsocks",
    "settings": {
      "method": "$SS_METHOD",
      "password": "$SS_PSK",
      "network": "tcp,udp"
    }
  }],
  "outbounds": [{"protocol": "freedom"}]
}
XEOF
        systemctl restart xray || error "xray failed to start (check journalctl -u xray)"
    fi
    iptables -C INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport "$PORT" -j ACCEPT
    iptables -C INPUT -p udp --dport "$PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport "$PORT" -j ACCEPT
    if command -v ufw > /dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "$PORT" > /dev/null 2>&1 || true
    fi
    persist_iptables
    B64="$(printf '%s:%s' "$SS_METHOD" "$SS_PSK" | base64 -w0)"
    echo "MULTIVPN-LINK: ss://$B64@$LINK_ADDR:$PORT#MultiVPN-SS"
    info "Shadowsocks ($SS_METHOD) ready on port $PORT."
else
    error "Unknown variant: $VARIANT (use vless|trojan|shadowsocks)"
fi

info "========================================="
info "  Xray ($VARIANT) is READY!"
info "========================================="
warn "Open TCP $PORT in your cloud firewall if needed."
