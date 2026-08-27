#!/bin/bash
# MultiVPN Server Tunnel Inventory (read-only)
# Usage: sudo bash scan-tunnels.sh
# Emits one MV-TUNNEL marker line per detected VPN server install:
#   MV-TUNNEL: wireguard [host|docker:<name>]
#   MV-TUNNEL: amnezia-<1.5|2|3|3.1> [host|docker:<name>]
#   MV-TUNNEL: openvpn
#   MV-TUNNEL: ikev2
# Nothing is installed, started or modified.
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
info() { echo -e "${GREEN}[+]${NC} $1"; }

[[ $EUID -ne 0 ]] && { echo "run as root"; exit 1; }

awg_version_of() { # awg_version_of <conf-text>
    if grep -qE '^RandomTrailers[[:space:]]*=' <<< "$1" || grep -qE '^DisableCookies[[:space:]]*=' <<< "$1"; then
        echo "3.1"
    elif grep -qE '^(HeaderProtectionKey|ContentPaddingAddition|RekeyAfterTime|RekeyTimeout|RejectAfterTime|KeepaliveTimeout|MaxHandshakeAttempts)[[:space:]]*=' <<< "$1"; then
        echo "3"
    elif grep -qE '^I[1-5][[:space:]]*=' <<< "$1" || grep -qE '^S[34][[:space:]]*=' <<< "$1"; then
        echo "2"
    else
        echo "1.5"
    fi
}

# ---- WireGuard / AmneziaWG (host, then docker) ----
WG_DONE=""
for f in /etc/wireguard/*.conf; do
    [ -f "$f" ] || continue
    TXT="$(cat "$f")"
    if grep -qE '^Jc[[:space:]]*=' <<< "$TXT"; then
        echo "MV-TUNNEL: amnezia-$(awg_version_of "$TXT") host"
    else
        echo "MV-TUNNEL: wireguard host"
    fi
    WG_DONE=1
    break
done
if [ -z "$WG_DONE" ] && command -v docker > /dev/null 2>&1; then
    for name in $(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -iE 'amnezia|wireguard|^a?wg' || true); do
        for p in /opt/amnezia/awg/awg0.conf /opt/amnezia/wireguard/wg0.conf \
                 /etc/amnezia/amnezia-wg/wg0.conf /etc/amnezia/wg0.conf /etc/wireguard/wg0.conf; do
            docker exec "$name" test -f "$p" < /dev/null 2>/dev/null || continue
            TXT="$(docker exec "$name" cat "$p" < /dev/null 2>/dev/null || true)"
            if grep -qE '^Jc[[:space:]]*=' <<< "$TXT"; then
                echo "MV-TUNNEL: amnezia-$(awg_version_of "$TXT") docker:$name"
            else
                echo "MV-TUNNEL: wireguard docker:$name"
            fi
            WG_DONE=1
            break
        done
        [ -n "$WG_DONE" ] && break
    done
fi

# ---- OpenVPN ----
if [ -d /etc/openvpn ] && ls /etc/openvpn/server* > /dev/null 2>&1; then
    echo "MV-TUNNEL: openvpn"
fi

# ---- IKEv2 / strongSwan ----
# Require an actual conn definition, not just a stray key under
# /etc/ipsec.d/private (other tooling drops keys there and produced a
# false positive that made the app skip IKEv2 provisioning).
if command -v ipsec > /dev/null 2>&1 && \
   ls /etc/ipsec.d/private/* > /dev/null 2>&1 && \
   grep -qE '^[[:space:]]*conn[[:space:]]+' /etc/ipsec.conf 2>/dev/null; then
    echo "MV-TUNNEL: ikev2"
fi

info "scan complete"
