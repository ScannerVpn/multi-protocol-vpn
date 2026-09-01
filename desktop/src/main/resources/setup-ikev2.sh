#!/bin/bash
# IKEv2 VPN Server Setup Script (Windows-client compatible)
# Usage: sudo bash setup-ikev2.sh [your-domain-or-ip] [p12-export-pass]
#   p12-export-pass: random passphrase the MultiVPN app generates per install;
#     manual runs without it fall back to the historical fixed "ikev2"
#     (accepted so servers set up by older app versions keep working).
# Supports: Ubuntu 20.04+, Debian 10+

set -e
# A failing stage inside a pipeline used to be invisible (`set -e` only checks
# the LAST command): `pki --pub | pki --issue` could half-fail and still write
# a truncated cert file. -o pipefail makes the whole pipeline fail instead.
set -o pipefail
umask 077

SERVER_ADDR="${1:-$(curl -fsS --max-time 10 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')}"
# Distinctive CA subject: the Windows client uses it to find and remove stale
# certificates from previous setups before importing the fresh ones.
CA_CN="Freebuff IKEv2 CA"
SERVER_CN="$SERVER_ADDR"
CLIENT_CN="vpnclient"
# Windows-compatible proposals. DELIBERATELY no 3DES, no SHA-1 and no
# MODP-1024: 3DES is a 64-bit-block cipher (Sweet32), SHA-1 is collision-broken
# and MODP-1024 is deprecated by NIST SP 800-57 (Logjam).
#
# CRITICAL PAIRING: a stock Windows 7..11 client proposes ONLY
# `3des-aes128-aes192-aes256-sha1-sha256-sha384-modp1024` by default, so
# removing the weak entries here WOULD break every connection with
# "policy match error" — unless the client profile is pinned to a strong
# policy. That is exactly what Vpn.kt's buildIkev2ConnectScript now does via
# Set-VpnConnectionIPsecConfiguration (AES256 / SHA256 / DHGroup14 / PFS2048),
# which matches the FIRST proposal below. The weak entries only ever served
# unconfigured clients, at the cost of letting a downgrade pick them for
# everyone. Change one side and you MUST change the other.
IKE_PROPOSAL="aes256-sha256-modp2048,aes256-sha384-modp2048,aes256gcm16-prfsha384-ecp384,aes256-sha256-ecp384,aes256-sha256-ecp256,aes128-sha256-modp2048"
# PFS2048 on the client means the child SA rekeys with DH group 14, so the ESP
# proposal has to offer modp2048 first.
ESP_PROPOSAL="aes256-sha256-modp2048,aes256-sha256,aes256gcm16,aes128-sha256"

# Windows requires an export password to import the PFX. The MultiVPN app
# passes a RANDOM per-install passphrase as $2 (see SshService.generateP12Password);
# it is only used for `openssl pkcs12 -export` on THIS box and stored DPAPI-
# encrypted in the client app. Manual runs default to the legacy fixed value.
CLIENT_P12_PASS="${2:-ikev2}"

export DEBIAN_FRONTEND=noninteractive

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[-]${NC} $1"; exit 1; }

[[ $EUID -ne 0 ]] && error "Run as root: sudo bash $0"

info "Setting up IKEv2 VPN server on: $SERVER_ADDR"

# Install strongSwan
echo iptables-persistent iptables-persistent/autosave_v4 boolean true | debconf-set-selections
echo iptables-persistent iptables-persistent/autosave_v6 boolean true | debconf-set-selections
info "Installing strongSwan..."
apt-get update -qq
apt-get install -y -qq strongswan strongswan-pki libcharon-extra-plugins libstrongswan-extra-plugins iptables-persistent > /dev/null 2>&1
# The tpm plugin fails to load on VPSes without a TPM 2.0 chip
# ("could not load libtss2-tcti-tabrmd.so.0") and can prevent charon from
# starting. Disable the plugin AND install the missing library as a fallback.
apt-get install -y -qq libtss2-tcti-tabrmd0 > /dev/null 2>&1 || true
mkdir -p /etc/strongswan.d/charon
cat > /etc/strongswan.d/charon/tpm.conf << 'TPMCONF'
tpm {
    load = no
}
TPMCONF

# Enable IP forwarding
info "Configuring kernel parameters..."
cat > /etc/sysctl.d/99-vpn.conf << 'SYSCTL'
net.ipv4.ip_forward = 1
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
SYSCTL
sysctl --system > /dev/null 2>&1

# BBR congestion control: measurably faster tunnels on lossy/filtered
# paths (the norm here). Best-effort - an old kernel without bbr just
# keeps its default cubic.
if modprobe tcp_bbr 2>/dev/null && sysctl -w net.core.default_qdisc=fq > /dev/null 2>&1 && \
   sysctl -w net.ipv4.tcp_congestion_control=bbr > /dev/null 2>&1; then
    grep -q tcp_congestion_control /etc/sysctl.d/99-vpn.conf || {
        printf 'net.core.default_qdisc = fq\nnet.ipv4.tcp_congestion_control = bbr\n' >> /etc/sysctl.d/99-vpn.conf
    }
    info "BBR congestion control enabled"
else
    info "BBR unavailable on this kernel - keeping default congestion control"
fi

# Generate PKI
PKI_DIR="/etc/ipsec.d"
info "Generating PKI certificates..."

# CA: RSA 4096. Leaf certs: RSA 2048 (Windows-compatible, fast handshake).
# Explicit SHA-256 signatures: recent Windows rejects SHA-1 signed chains.
pki --gen --type rsa --size 4096 --outform pem > "$PKI_DIR/caKey.pem"
pki --self --ca --lifetime 3650 --digest sha256 --in "$PKI_DIR/caKey.pem" --type rsa \
    --dn "CN=$CA_CN" --outform pem > "$PKI_DIR/caCert.pem"

pki --gen --type rsa --size 2048 --outform pem > "$PKI_DIR/serverKey.pem"
pki --pub --in "$PKI_DIR/serverKey.pem" --type rsa | \
    pki --issue --lifetime 1825 --digest sha256 --cacert "$PKI_DIR/caCert.pem" \
    --cakey "$PKI_DIR/caKey.pem" --dn "CN=$SERVER_CN" \
    --san "$SERVER_ADDR" --flag serverAuth --flag ikeIntermediate \
    --outform pem > "$PKI_DIR/serverCert.pem"

pki --gen --type rsa --size 2048 --outform pem > "$PKI_DIR/clientKey.pem"
pki --pub --in "$PKI_DIR/clientKey.pem" --type rsa | \
    pki --issue --lifetime 1825 --digest sha256 --cacert "$PKI_DIR/caCert.pem" \
    --cakey "$PKI_DIR/caKey.pem" --dn "CN=$CLIENT_CN" \
    --flag clientAuth \
    --outform pem > "$PKI_DIR/clientCert.pem"

# Generate client PKCS#12 with the full chain.
openssl pkcs12 -export -inkey "$PKI_DIR/clientKey.pem" -in "$PKI_DIR/clientCert.pem" \
    -certfile "$PKI_DIR/caCert.pem" -out "$PKI_DIR/client.p12" \
    -passout "pass:$CLIENT_P12_PASS"

cp "$PKI_DIR/caCert.pem" "$PKI_DIR/cacerts/"
cp "$PKI_DIR/serverCert.pem" "$PKI_DIR/certs/"
cp "$PKI_DIR/serverKey.pem" "$PKI_DIR/private/"

# Configure ipsec.conf
# NOTE: no `rightsendcert=never` here — the server must request the client
# certificate (CERTREQ) or Windows may not send it and AUTH fails with
# "Policy match error". No `rekey=no` either, or the tunnel drops instead of
# rekeying.
info "Writing ipsec.conf..."
cat > /etc/ipsec.conf << EOF
config setup
    charondebug="ike 2, knl 2, cfg 2"
    uniqueids=no

conn ikev2-vpn
    type=tunnel
    keyexchange=ikev2
    left=%defaultroute
    leftid=$SERVER_ADDR
    leftcert=serverCert.pem
    leftsendcert=always
    leftsubnet=0.0.0.0/0
    right=%any
    rightauth=pubkey
    rightsourceip=10.10.10.0/24
    rightdns=8.8.8.8,1.1.1.1
    ike=$IKE_PROPOSAL
    esp=$ESP_PROPOSAL
    dpdaction=clear
    dpddelay=30s
    fragmentation=yes
    auto=add
EOF

# Configure ipsec.secrets
cat > /etc/ipsec.secrets << EOF
: RSA serverKey.pem
EOF

# Firewall rules (scoped: only allow IKE traffic and forward for our subnet)
info "Configuring firewall..."
# Insert at the top of INPUT: with ufw active, appended rules come after its
# DROP rules and never match.
iptables -C INPUT -p udp --dport 500 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 500 -j ACCEPT
iptables -C INPUT -p udp --dport 4500 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport 4500 -j ACCEPT
# ESP may arrive unencapsulated when there is no NAT in the path.
iptables -C INPUT -p esp -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p esp -j ACCEPT
# Do NOT change the global FORWARD policy — it weakens Docker/container isolation.
# Use scoped rules instead: only forward traffic from our VPN subnet to WAN,
# plus the established way back.
#
# `-i eth+` was WRONG: modern Debian/Ubuntu use predictable names (ens3,
# enp1s0, eno1), so the rule matched nothing and forwarding silently failed on
# any host whose FORWARD policy is DROP (i.e. every host running Docker).
# Traffic from the VPN subnet arrives on the strongSwan policy path, not on a
# named interface, so match by SOURCE SUBNET only.
VPN_SUBNET="10.10.10.0/24"
WAN_IF="$(ip route show default 2>/dev/null | awk '{print $5; exit}' || true)"
if [ -n "$WAN_IF" ]; then
    iptables -C FORWARD -s "$VPN_SUBNET" -o "$WAN_IF" -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD 1 -s "$VPN_SUBNET" -o "$WAN_IF" -j ACCEPT
    iptables -C FORWARD -d "$VPN_SUBNET" -i "$WAN_IF" -m conntrack \
        --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD 1 -d "$VPN_SUBNET" -i "$WAN_IF" -m conntrack \
            --ctstate RELATED,ESTABLISHED -j ACCEPT
    iptables -t nat -C POSTROUTING -s "$VPN_SUBNET" -o "$WAN_IF" -j MASQUERADE 2>/dev/null || \
        iptables -t nat -A POSTROUTING -s "$VPN_SUBNET" -o "$WAN_IF" -j MASQUERADE
else
    warn "no default route found - NAT/forwarding rules are not interface-scoped"
    iptables -C FORWARD -s "$VPN_SUBNET" -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD 1 -s "$VPN_SUBNET" -j ACCEPT
    iptables -C FORWARD -d "$VPN_SUBNET" -m conntrack \
        --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD 1 -d "$VPN_SUBNET" -m conntrack \
            --ctstate RELATED,ESTABLISHED -j ACCEPT
    iptables -t nat -C POSTROUTING -s "$VPN_SUBNET" -j MASQUERADE 2>/dev/null || \
        iptables -t nat -A POSTROUTING -s "$VPN_SUBNET" -j MASQUERADE
fi
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow 500/udp > /dev/null 2>&1 || true
    ufw allow 4500/udp > /dev/null 2>&1 || true
    ufw route allow from "$VPN_SUBNET" > /dev/null 2>&1 || true
fi
# Persist, or the rules vanish on the next reboot and clients connect with no
# internet — the project's most confusing failure mode. iptables-persistent is
# installed above, so netfilter-persistent is normally present here.
if command -v netfilter-persistent > /dev/null 2>&1; then
    netfilter-persistent save > /dev/null 2>&1 && info "firewall rules persisted" || \
        warn "netfilter-persistent save failed - rules will be lost on reboot"
elif command -v iptables-save > /dev/null 2>&1; then
    mkdir -p /etc/iptables 2>/dev/null || true
    iptables-save > /etc/iptables/rules.v4 2>/dev/null && \
        info "firewall rules saved to /etc/iptables/rules.v4" || \
        warn "could not persist firewall rules - they will be lost on reboot"
else
    warn "could NOT persist firewall rules - they will be lost on reboot"
fi

# Start strongSwan
info "Starting strongSwan..."
systemctl enable strongswan-starter > /dev/null 2>&1
systemctl restart strongswan-starter || warn "strongSwan failed to start; check 'systemctl status strongswan-starter' on the server"

# Save client config
OUTPUT_DIR="/root/ikev2-client"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
cp "$PKI_DIR/client.p12" "$OUTPUT_DIR/"
cp "$PKI_DIR/caCert.pem" "$OUTPUT_DIR/ca.crt"

cat > "$OUTPUT_DIR/client.sswan" << EOF
conn $SERVER_ADDR
    type=tunnel
    keyexchange=ikev2
    left=%defaultroute
    right=$SERVER_ADDR
    rightid=$SERVER_ADDR
    rightca=caCert.pem
    ike=$IKE_PROPOSAL
    esp=$ESP_PROPOSAL
    auto=add
EOF

info "========================================="
info "  IKEv2 VPN Server is READY!"
info "========================================="
info ""
info "Server: $SERVER_ADDR"
info "Client files saved to: $OUTPUT_DIR/"
info ""
info "  client.p12   - Client certificate (import in VPN app)"
info "  ca.crt       - CA certificate"
info "  client.sswan - Config file (import in VPN app)"
info ""
warn "Open UDP ports 500 and 4500 in your cloud firewall!"
