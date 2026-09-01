#!/bin/bash
# OpenVPN Server Setup Script
# Usage: sudo bash setup-openvpn.sh <server-ip>
# - Detects an existing system OpenVPN (/etc/openvpn/server*.conf) and only
#   issues a new client certificate with the existing PKI.
# - Fresh install: openvpn + easy-rsa PKI, udp/1194, tls-crypt, AES-256-GCM.
#   A single-file client .ovpn is written to /root/multivpn-openvpn/client.ovpn
set -e
# A failing pipeline stage used to pass silently (set -e only checks the LAST
# command), so a half-failed `grep | awk` handed an empty PORT/PROTO onward.
set -o pipefail
# Certificates and keys are generated below: never world-readable.
umask 077

SERVER_ADDR="${1:?usage: setup-openvpn.sh <ip>}"
CLIENT_OUT="/root/multivpn-openvpn"
export DEBIAN_FRONTEND=noninteractive

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[-]${NC} $1"; exit 1; }

# Rule persistence: without this the NAT/INPUT rules vanish on the next reboot
# and clients connect but have NO internet.
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

# Docker sets FORWARD's policy to DROP, so NAT alone forwarded nothing on any
# container host: the client connected and had no internet. Scoped, idempotent.
allow_forward() { # allow_forward <subnet-cidr> <wan-if or empty>
    local subnet="$1" wan="$2"
    if [ -n "$wan" ]; then
        iptables -C FORWARD -s "$subnet" -o "$wan" -j ACCEPT 2>/dev/null || \
            iptables -I FORWARD 1 -s "$subnet" -o "$wan" -j ACCEPT
        iptables -C FORWARD -d "$subnet" -i "$wan" -m conntrack \
            --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
            iptables -I FORWARD 1 -d "$subnet" -i "$wan" -m conntrack \
                --ctstate RELATED,ESTABLISHED -j ACCEPT
    else
        iptables -C FORWARD -s "$subnet" -j ACCEPT 2>/dev/null || \
            iptables -I FORWARD 1 -s "$subnet" -j ACCEPT
        iptables -C FORWARD -d "$subnet" -m conntrack \
            --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
            iptables -I FORWARD 1 -d "$subnet" -m conntrack \
                --ctstate RELATED,ESTABLISHED -j ACCEPT
    fi
}

[[ $EUID -ne 0 ]] && error "Run as root: sudo bash $0"
mkdir -p "$CLIENT_OUT"; chmod 700 "$CLIENT_OUT"

SERVER_CONF=""
for p in /etc/openvpn/server/server.conf /etc/openvpn/server.conf; do
    [ -f "$p" ] && SERVER_CONF="$p" && break
done

EASYRSA=""
find_easyrsa() {
    for d in /usr/share/easy-rsa /etc/openvpn/easy-rsa /root/easy-rsa; do
        if [ -x "$d/easyrsa" ] || [ -f "$d/easyrsa" ]; then EASYRSA="$d"; return 0; fi
    done
    return 1
}

if [ -n "$SERVER_CONF" ]; then
    info "Existing OpenVPN detected ($SERVER_CONF) - issuing a new client certificate."
    find_easyrsa || error "easy-rsa not found; cannot issue certs for the existing installation."
    # locate the pki used by this server (common layouts)
    PKI=""
    for d in /etc/openvpn/pki /etc/openvpn/easy-rsa/pki /root/easy-rsa/pki; do
        [ -f "$d/vars" ] || true
        if [ -f "$d/ca.crt" ]; then PKI="$d"; break; fi
    done
    [ -z "$PKI" ] && error "Could not find the CA/pki of the existing OpenVPN."
    [ -z "$PROTO" ] && PROTO="udp"
    PORT="$(grep -m1 -oE '^port[[:space:]]+[0-9]+' "$SERVER_CONF" | grep -oE '[0-9]+' || echo 1194)"
    PROTO="$(grep -m1 -oE '^proto[[:space:]]+(udp|tcp)' "$SERVER_CONF" | awk '{print $2}' || true)"
    [ -z "$PROTO" ] && PROTO="udp"
    CA="$PKI/ca.crt"; TA=""
    for t in "$PKI/tls-crypt.key" "$PKI/ta.key" /etc/openvpn/server/tls-crypt.key /etc/openvpn/ta.key; do
        [ -f "$t" ] && TA="$t" && break
    done
else
    info "Installing OpenVPN + easy-rsa..."
    apt-get update -qq
    apt-get install -y -qq openvpn easy-rsa > /dev/null 2>&1
    find_easyrsa || error "easy-rsa missing after install"
    PKI="/etc/openvpn/pki"
    mkdir -p /etc/openvpn/server "$PKI"
    cd "$PKI"
    export EASYRSA_PKI="$PKI"
    # Guard: init-pki fails on non-empty PKI dir (partial-install abort).
    # Only re-init if the CA doesn't exist yet.
    if [ ! -f "$PKI/ca.crt" ]; then
        "$EASYRSA/easyrsa" --batch init-pki > /dev/null 2>&1
        "$EASYRSA/easyrsa" --batch --days=3650 build-ca nopass > /dev/null 2>&1
    fi
    "$EASYRSA/easyrsa" --batch --days=3650 gen-req server nopass > /dev/null 2>&1
    "$EASYRSA/easyrsa" --batch sign-req server server > /dev/null 2>&1
    openvpn --genkey secret "$PKI/tls-crypt.key" > /dev/null 2>&1
    cp "$PKI/issued/server.crt" "$PKI/private/server.key" "$PKI/ca.crt" "$PKI/tls-crypt.key" /etc/openvpn/server/ 2>/dev/null || true
    PORT=1194; PROTO=udp; CA="$PKI/ca.crt"; TA="$PKI/tls-crypt.key"

    cat > /etc/openvpn/server/server.conf << OEOF
port $PORT
proto $PROTO
dev tun
ca /etc/openvpn/server/ca.crt
cert /etc/openvpn/server/server.crt
key /etc/openvpn/server/server.key
tls-crypt /etc/openvpn/server/tls-crypt.key
dh none
ecdh-curve prime256v1
server 10.8.0.0 255.255.255.0
ifconfig-pool-persist /var/log/openvpn/ipp.txt
push "redirect-gateway def1 bypass-dhcp"
push "dhcp-option DNS 1.1.1.1"
push "dhcp-option DNS 8.8.8.8"
keepalive 10 120
cipher AES-256-GCM
auth SHA256
user nobody
group nogroup
persist-key
persist-tun
status /var/log/openvpn/status.log
verb 1
OEOF

    echo 'net.ipv4.ip_forward = 1' > /etc/sysctl.d/99-multivpn-ovpn.conf
    sysctl --system > /dev/null 2>&1 || true
    # BBR congestion control (best-effort; see setup-ikev2.sh).
    if modprobe tcp_bbr 2>/dev/null && sysctl -w net.core.default_qdisc=fq > /dev/null 2>&1 && \
       sysctl -w net.ipv4.tcp_congestion_control=bbr > /dev/null 2>&1; then
        printf 'net.core.default_qdisc = fq\nnet.ipv4.tcp_congestion_control = bbr\n' >> /etc/sysctl.d/99-multivpn-ovpn.conf
        info "BBR congestion control enabled"
    else
        info "BBR unavailable on this kernel - keeping default congestion control"
    fi
    WAN_IF="$(ip route show default 2>/dev/null | awk '{print $5; exit}' || true)"
    if [ -n "$WAN_IF" ]; then
        iptables -t nat -C POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null || \
            iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "$WAN_IF" -j MASQUERADE
    else
        warn "no default route found - NAT rule is not interface-scoped"
        iptables -t nat -C POSTROUTING -s 10.8.0.0/24 -j MASQUERADE 2>/dev/null || \
            iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -j MASQUERADE
    fi
    # NAT without FORWARD is a no-op wherever the policy is DROP (Docker hosts).
    allow_forward "10.8.0.0/24" "$WAN_IF"
    iptables -C INPUT -p "$PROTO" --dport "$PORT" -j ACCEPT 2>/dev/null || \
        iptables -I INPUT 1 -p "$PROTO" --dport "$PORT" -j ACCEPT
    if command -v ufw > /dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "$PORT/$PROTO" > /dev/null 2>&1 || true
        ufw route allow from 10.8.0.0/24 > /dev/null 2>&1 || true
    fi
    persist_iptables
    systemctl enable --now openvpn-server@server > /dev/null 2>&1 || \
        systemctl enable --now openvpn@server > /dev/null 2>&1 || warn "openvpn service did not start; check systemctl status openvpn-server@server"
fi

# ---------------------------------------------------------- client cert
CNAME="multivpn-$(date +%s)"
cd "$(dirname "$PKI")"
export EASYRSA_PKI="$PKI"
"$EASYRSA/easyrsa" --batch --days=3650 gen-req "$CNAME" nopass > /dev/null 2>&1
"$EASYRSA/easyrsa" --batch sign-req client "$CNAME" > /dev/null 2>&1

# The server cert's CN (easy-rsa defaults to "server" for a fresh install,
# but an existing PKI may carry whatever CN it was created with). The client
# must verify against the REAL CN or the handshake fails with
# "VERIFY X509NAME ERROR" before any TLS exchange.
SERVER_CRT=""
for p in "$PKI/issued/server.crt" "/etc/openvpn/server/server.crt" "/etc/openvpn/server.conf" ; do
    if [ -f "$p" ] && [[ "$p" == *.crt ]]; then SERVER_CRT="$p"; break; fi
done
SERVER_CN=""
if [ -n "$SERVER_CRT" ]; then
    SERVER_CN="$(openssl x509 -in "$SERVER_CRT" -noout -subject 2>/dev/null | sed -n 's/.*CN=\([^,/]*\).*/\1/p' | tr -d ' ' || true)"
fi
[ -z "$SERVER_CN" ] && SERVER_CN="server"

# ---------------------------------------------------------- client .ovpn
{
cat << OEOF
client
dev tun
proto $PROTO
remote $SERVER_ADDR $PORT
resolv-retry infinite
nobind
persist-key
persist-tun
verify-x509-name $SERVER_CN name
remote-cert-tls server
cipher AES-256-GCM
auth SHA256
verb 2
<ca>
$(cat "$CA")
</ca>
<cert>
$(sed -ne '/BEGIN CERTIFICATE/,/END CERTIFICATE/p' "$PKI/issued/$CNAME.crt")
</cert>
<key>
$(cat "$PKI/private/$CNAME.key")
</key>
OEOF
if [ -n "$TA" ]; then
    # Detect whether ta.key is a tls-crypt key (binary blob) or a CA key
    # used with tls-auth (plain PEM text). Use the correct embed format.
    #
    # Both branches write to the GROUP's stdout, not straight to the file.
    # They used to use `>> "$CLIENT_OUT/client.ovpn"` from inside a
    # `{ ... } > "$CLIENT_OUT/client.ovpn"` group — two independent writers to
    # one file, whose interleaving is undefined.
    if head -c 4 "$TA" | grep -qi 'BEGIN'; then
        # Plain-text key used with tls-auth. `key-direction 1` is MANDATORY on
        # the client (the server side is 0); without it OpenVPN aborts with
        # "Cannot open --tls-auth key file" / HMAC verification failures.
        printf '<tls-auth>\n%s\n</tls-auth>\nkey-direction 1\n' "$(cat "$TA")"
    else
        # Binary tls-crypt key — no key-direction, it is bidirectional.
        printf '<tls-crypt>\n%s\n</tls-crypt>\n' "$(cat "$TA")"
    fi
fi
} > "$CLIENT_OUT/client.ovpn"
chmod 600 "$CLIENT_OUT/client.ovpn"

info "========================================="
info "  OpenVPN is READY! (client: $CNAME)"
info "========================================="
info "Client config: $CLIENT_OUT/client.ovpn ($PROTO/$PORT)"
warn "Open ${PROTO^^} $PORT in your cloud firewall!"
