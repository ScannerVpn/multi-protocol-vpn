#!/bin/bash
# WireGuard / AmneziaWG VPN Server Setup Script
# Usage: sudo bash setup-wireguard.sh <server-ip> [standard|amnezia] [awg-version]
#   awg-version (amnezia mode only): 1.5 | 2 | 3 | 3.1   (default 1.5)
#
# Detection (nothing is reinstalled when found):
#   1. any /etc/wireguard/*.conf on the host (wg-quick format, any interface
#      name; flavor detected by the presence of Jc= obfuscation params)
#   2. an Amnezia docker container (installed by the Amnezia VPN app) —
#      peers are added inside the container
# Only when nothing is found does a fresh install run (wireguard-tools, or
# amneziawg from ppa:amnezia/ppa when mode=amnezia).
#
# AWG protocol versions and their parameters:
#   1.5  Jc/Jmin/Jmax/S1/S2/H1-H4
#   2    + S3/S4, I1-I5 signature packets (CPS)
#   3    + HeaderProtectionKey, ContentPaddingAddition, timing ranges
#   3.1  + RandomTrailers, DisableCookies
set -e

SERVER_ADDR="${1:?usage: setup-wireguard.sh <ip> [standard|amnezia] [awg-version]}"
MODE="${2:-standard}"
CLIENT_OUT="/root/multivpn-wg"

# ------------------------------------------------------- AWG param templates
AWG_VERSION="${3:-1.5}"
case "$AWG_VERSION" in
    1.5|2|3|3.1) ;;
    *) AWG_VERSION="1.5" ;;
esac

# Randomized per-install values: no two servers share the same signature.
# H1..H4 get disjoint "min-max" ranges (amneziawg-go picks one value from
# the range per packet; truncating the range silently breaks the handshake).
rand30() { echo $(( (RANDOM * 32768 + RANDOM) % 400000000 )); }
header_range() { # header_range <index 1..4>
    local lo span
    lo=$(( $(rand30) + $1 * 400000000 ))
    span=$(( $(rand30) % 100000000 + 1000 ))
    echo "$lo-$((lo + span))"
}
hpk() { # 32-byte hex key for AWG 3.x header protection
    if command -v openssl > /dev/null 2>&1; then openssl rand -hex 32; return; fi
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
}

awg_params() { # emits the [Interface] obfuscation block for $AWG_VERSION
    echo "Jc = 4"
    echo "Jmin = 40"
    echo "Jmax = 70"
    echo "S1 = 30"
    echo "S2 = 30"
    case "$AWG_VERSION" in
        2|3|3.1) echo "S3 = 15"; echo "S4 = 15" ;;
    esac
    case "$AWG_VERSION" in
        3|3.1)
            # S1..S4 must be >= 12 when header protection is enabled (they are here).
            echo "HeaderProtectionKey = $(hpk)"
            echo "ContentPaddingAddition = 10"
            echo "MaxHandshakeAttempts = 10"
            [ "$AWG_VERSION" = "3.1" ] && echo "RandomTrailers = on"
            ;;
    esac
    local i=1
    while [ "$i" -le 4 ]; do echo "H$i = $(header_range "$i")"; i=$((i + 1)); done
}

export DEBIAN_FRONTEND=noninteractive

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[-]${NC} $1"; exit 1; }

[[ $EUID -ne 0 ]] && error "Run as root: sudo bash $0"
mkdir -p "$CLIENT_OUT"; chmod 700 "$CLIENT_OUT"

ipt_ensure() { iptables -C "$@" 2>/dev/null || iptables -I INPUT 1 "$@"; }

# ---------------------------------------------------------------- detection
# Requested flavor decides the search order: amnezia mode prefers an
# AmneziaWG (awg) installation, standard mode prefers a plain WireGuard one.
WG_CONF=""
DOCKER_AWG=""
CONF_IN=""

find_docker_wg() { # find_docker_wg <want-awg: yes|no>
    command -v docker > /dev/null 2>&1 || return 1
    local want="$1" name path
    for name in $(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -iE 'amnezia|wireguard|^a?wg' || true); do
        for path in /opt/amnezia/awg/awg0.conf /opt/amnezia/wireguard/wg0.conf \
                    /etc/amnezia/amnezia-wg/wg0.conf /etc/amnezia/wg0.conf /etc/wireguard/wg0.conf; do
            docker exec "$name" test -f "$path" 2>/dev/null || continue
            local is_awg=no
            case "$path" in *awg*) is_awg=yes ;; esac
            if docker exec "$name" grep -qE '^Jc[[:space:]]*=' "$path" 2>/dev/null; then is_awg=yes; fi
            if [ "$want" = "$is_awg" ]; then
                DOCKER_AWG="$name"; CONF_IN="$path"; return 0
            fi
            # remember as fallback
            if [ -z "$DOCKER_AWG" ]; then DOCKER_AWG_FB="$name"; CONF_IN_FB="$path"; fi
        done
    done
    return 1
}

find_host_wg() { # find_host_wg <want-awg: yes|no>
    local want="$1" f is_awg
    for f in /etc/wireguard/*.conf; do
        [ -f "$f" ] || continue
        is_awg=no
        grep -qE '^Jc[[:space:]]*=' "$f" && is_awg=yes
        if [ "$want" = "$is_awg" ]; then WG_CONF="$f"; return 0; fi
        [ -z "$WG_CONF_FB" ] && WG_CONF_FB="$f"
    done
    return 1
}

if [ "$MODE" = "amnezia" ]; then
    find_docker_wg yes || find_host_wg yes || true
else
    find_host_wg no || find_docker_wg no || true
fi
# nothing of the requested flavor: fall back to whatever exists
if [ -z "$WG_CONF" ] && [ -z "$DOCKER_AWG" ]; then
    if [ -n "$WG_CONF_FB" ]; then
        WG_CONF="$WG_CONF_FB"
    elif [ -n "$DOCKER_AWG_FB" ]; then
        DOCKER_AWG="$DOCKER_AWG_FB"; CONF_IN="$CONF_IN_FB"
    fi
fi

IFACE="wg0"
TOOL=""        # wg | awg
QUICK=""       # wg-quick | awg-quick

if [ -n "$DOCKER_AWG" ]; then
    IFACE="$(basename "$CONF_IN" .conf)"
    TOOL=wg
    docker exec "$DOCKER_AWG" sh -c 'command -v awg' > /dev/null 2>&1 && TOOL=awg
    run_tool()   { docker exec "$DOCKER_AWG" "$TOOL" "$@" < /dev/null; }
    read_conf()  { docker exec "$DOCKER_AWG" cat "$CONF_IN" < /dev/null; }
    append_peer() { # append_peer <text>
        printf '%s\n' "$1" | docker exec -i "$DOCKER_AWG" sh -c "cat >> '$CONF_IN'"
    }
    info "Existing Amnezia docker container detected ($DOCKER_AWG, $TOOL, interface $IFACE) - adding a new peer."
elif [ -n "$WG_CONF" ]; then
    IFACE="$(basename "$WG_CONF" .conf)"
    if grep -qE '^Jc[[:space:]]*=' "$WG_CONF"; then TOOL=awg; QUICK=awg-quick; else TOOL=wg; QUICK=wg-quick; fi
    run_tool()   { "$TOOL" "$@"; }
    read_conf()  { cat "$WG_CONF"; }
    append_peer() { printf '%s\n' "$1" >> "$WG_CONF"; }
    info "Existing $([ "$TOOL" = awg ] && echo AmneziaWG || echo WireGuard) detected ($WG_CONF) - adding a new peer."
else
    # ------------------------------------------------------- fresh install
    if [ "$MODE" = "amnezia" ]; then
        info "Installing AmneziaWG..."
        apt-get install -y -qq software-properties-common > /dev/null 2>&1
        add-apt-repository -y ppa:amnezia/ppa > /dev/null 2>&1
        apt-get update -qq
        apt-get install -y -qq amneziawg amneziawg-tools > /dev/null 2>&1 || \
            error "Failed to install amneziawg from ppa:amnezia/ppa. Use 'standard' mode on this distro."
        TOOL=awg; QUICK=awg-quick
    else
        info "Installing WireGuard (official)..."
        apt-get update -qq
        apt-get install -y -qq wireguard-tools > /dev/null 2>&1
        TOOL=wg; QUICK=wg-quick
    fi
    command -v "$TOOL" > /dev/null 2>&1 || error "$TOOL not found after install"
    WG_CONF="/etc/wireguard/wg0.conf"
    IFACE="wg0"
    run_tool()   { "$TOOL" "$@"; }
    read_conf()  { cat "$WG_CONF"; }
    append_peer() { printf '%s\n' "$1" >> "$WG_CONF"; }

    info "Configuring kernel parameters..."
    cat > /etc/sysctl.d/99-multivpn-wg.conf << 'SYSCTL'
net.ipv4.ip_forward = 1
SYSCTL
    sysctl --system > /dev/null 2>&1 || true

    info "Generating server keys..."
    umask 077
    SERVER_PRIV="$(run_tool genkey)"
    SERVER_PUB="$(echo "$SERVER_PRIV" | run_tool pubkey)"
    {
        echo "[Interface]"
        echo "Address = 10.2.0.1/24"
        echo "ListenPort = 51820"
        echo "PrivateKey = $SERVER_PRIV"
        if [ "$TOOL" = "awg" ]; then awg_params; fi
    } > "$WG_CONF"
fi

# ------------------------------------------------- subnet / port discovery
SUBNET_PREFIX="$(read_conf | grep -m1 -oE '^[[:space:]]*Address[[:space:]]*=[[:space:]]*[0-9]+\.[0-9]+\.[0-9]+\.' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.' || true)"
[ -z "$SUBNET_PREFIX" ] && SUBNET_PREFIX="10.2.0."
PORT="$(read_conf | grep -m1 -oE '^ListenPort[[:space:]]*=[[:space:]]*[0-9]+' | grep -oE '[0-9]+' || true)"
[ -z "$PORT" ] && PORT="51820"

# NAT + firewall (idempotent, both host and fresh paths)
if [ -z "$DOCKER_AWG" ]; then
    WAN_IF="$(ip route show default 2>/dev/null | awk '{print $5; exit}')"
    if [ -n "$WAN_IF" ]; then
        iptables -t nat -C POSTROUTING -s ${SUBNET_PREFIX}0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null || \
            iptables -t nat -A POSTROUTING -s ${SUBNET_PREFIX}0/24 -o "$WAN_IF" -j MASQUERADE
    else
        iptables -t nat -C POSTROUTING -s ${SUBNET_PREFIX}0/24 -j MASQUERADE 2>/dev/null || \
            iptables -t nat -A POSTROUTING -s ${SUBNET_PREFIX}0/24 -j MASQUERADE
    fi
    ipt_ensure -p udp --dport "$PORT" -j ACCEPT
    if command -v ufw > /dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "$PORT/udp" > /dev/null 2>&1 || true
    fi
fi

# ------------------------------------------------- next client IP + peer
LAST="$(read_conf | grep -oE "${SUBNET_PREFIX}[0-9]+" | awk -F. '{print $4}' | sort -n | tail -1)"
N=$(( ${LAST:-1} + 1 ))
CLIENT_IP="${SUBNET_PREFIX}${N}"
info "Client IP will be $CLIENT_IP"

umask 077
# Generate keys inside the same environment as the tool (container-safe).
if [ -n "$DOCKER_AWG" ]; then
    docker exec "$DOCKER_AWG" sh -c "$TOOL genkey > /tmp/mv.priv && $TOOL pubkey < /tmp/mv.priv > /tmp/mv.pub && $TOOL genpsk > /tmp/mv.psk" < /dev/null
    CLIENT_PRIV="$(docker exec "$DOCKER_AWG" cat /tmp/mv.priv < /dev/null)"
    CLIENT_PUB="$(docker exec "$DOCKER_AWG" cat /tmp/mv.pub < /dev/null)"
    CLIENT_PSK="$(docker exec "$DOCKER_AWG" cat /tmp/mv.psk < /dev/null)"
    PEER_TXT="$(printf '\n[Peer]\n# multivpn-client-%s\nPublicKey = %s\nPresharedKey = %s\nAllowedIPs = %s/32\n' \
        "$N" "$CLIENT_PUB" "$CLIENT_PSK" "$CLIENT_IP")"
    append_peer "$PEER_TXT"
    docker exec "$DOCKER_AWG" sh -c "printf '[Peer]\nPublicKey = %s\nPresharedKey = %s\nAllowedIPs = %s/32\n' '$CLIENT_PUB' '$CLIENT_PSK' '$CLIENT_IP' > /tmp/mv.peer && $TOOL addconf $IFACE /tmp/mv.peer" < /dev/null || true
    # Always clean up temp files even if peer addition failed.
    docker exec "$DOCKER_AWG" sh -c 'rm -f /tmp/mv.priv /tmp/mv.pub /tmp/mv.psk /tmp/mv.peer' < /dev/null 2>/dev/null || true
else
    CLIENT_PRIV="$(run_tool genkey)"
    CLIENT_PUB="$(printf '%s' "$CLIENT_PRIV" | run_tool pubkey)"
    CLIENT_PSK=""
    PEER_TXT="$(printf '\n[Peer]\n# multivpn-client-%s\nPublicKey = %s\nAllowedIPs = %s/32\n' \
        "$N" "$CLIENT_PUB" "$CLIENT_IP")"
    append_peer "$PEER_TXT"
    if run_tool show "$IFACE" > /dev/null 2>&1; then
        printf '[Peer]\nPublicKey = %s\nAllowedIPs = %s/32\n' "$CLIENT_PUB" "$CLIENT_IP" > /tmp/multivpn_peer
        run_tool addconf "$IFACE" /tmp/multivpn_peer && info "Peer added to the running interface."
        rm -f /tmp/multivpn_peer
    else
        "$QUICK" up "$IFACE" && info "Interface $IFACE started."
        systemctl enable "wg-quick@${IFACE}" > /dev/null 2>&1 || true
    fi
fi

# ---------------------------------------------------------- client config
SERVER_PUB="$(run_tool show "$IFACE" public-key)"
# Every obfuscation parameter is copied VERBATIM, including the "min-max"
# ranges of H1..H4: amneziawg-go picks the actual header value from the range,
# so truncating it to the first number makes the handshake fail silently
# (verified 2026-08-24 against an Amnezia docker server). The list covers
# AWG 1.5 through 3.1 (I1-I5, HeaderProtectionKey, timing, trailers...).
AWG_LINES="$(read_conf | grep -E '^(Jc|Jmin|Jmax|S1|S2|S3|S4|H1|H2|H3|H4|I1|I2|I3|I4|I5|HeaderProtectionKey|ContentPaddingAddition|RekeyAfterTime|RekeyTimeout|RejectAfterTime|KeepaliveTimeout|MaxHandshakeAttempts|RandomTrailers|DisableCookies)[[:space:]]*=' || true)"
{
    echo "[Interface]"
    echo "PrivateKey = $CLIENT_PRIV"
    echo "Address = $CLIENT_IP/32"
    echo "DNS = 1.1.1.1, 8.8.8.8"
    [ -n "$AWG_LINES" ] && printf '%s\n' "$AWG_LINES"
    echo ""
    echo "[Peer]"
    echo "PublicKey = $SERVER_PUB"
    [ -n "$CLIENT_PSK" ] && echo "PresharedKey = $CLIENT_PSK"
    echo "Endpoint = ${SERVER_ADDR}:${PORT}"
    # Only include ::/0 when the server actually has IPv6 support; otherwise
    # it silently drops all IPv6 traffic through the tunnel (blackhole).
    HAS_IPV6=$(ip -6 addr show 2>/dev/null | grep -c 'inet6' || true)
    if [ "$HAS_IPV6" -gt 0 ]; then
        echo "AllowedIPs = 0.0.0.0/0, ::/0"
    else
        echo "AllowedIPs = 0.0.0.0/0"
    fi
    echo "PersistentKeepalive = 25"
    true
} > "$CLIENT_OUT/client.conf"
chmod 600 "$CLIENT_OUT/client.conf"

info "========================================="
info "  ${TOOL}-based VPN is READY!"
info "========================================="
if [ -n "$DOCKER_AWG" ]; then
    info "Flavor: AmneziaWG (docker container $DOCKER_AWG)"
else
    info "Flavor:  $([ "$TOOL" = "awg" ] && echo AmneziaWG || echo WireGuard)"
fi
[ "$TOOL" = "awg" ] && info "AWG protocol version: $AWG_VERSION (requested)"
info "Client config: $CLIENT_OUT/client.conf (client IP $CLIENT_IP)"
[ -n "$DOCKER_AWG" ] && warn "Docker-detected mode: NAT/firewall are managed by the Amnezia container."
warn "Make sure UDP $PORT is open in your cloud firewall!"
