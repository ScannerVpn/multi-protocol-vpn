#!/bin/bash
# IKEv2 VPN Server Setup Script (Windows-client compatible)
# Usage: sudo bash setup-ikev2.sh [your-domain-or-ip] [p12-export-pass]
#   p12-export-pass: random passphrase the MultiVPN app generates per install;
#     manual runs without it fall back to the historical fixed "ikev2"
#     (accepted so servers set up by older app versions keep working).
# Supports: Ubuntu 20.04+, Debian 10+

set -e
umask 077

SERVER_ADDR="${1:-$(curl -s ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')}"
# Distinctive CA subject: the Windows client uses it to find and remove stale
# certificates from previous setups before importing the fresh ones.
CA_CN="Freebuff IKEv2 CA"
SERVER_CN="$SERVER_ADDR"
CLIENT_CN="vpnclient"
# Windows-compatible proposals: Windows 11 prefers ECP DH groups, Windows 10
# MODP; keep both plus a 3DES fallback so any stock Windows client matches.
IKE_PROPOSAL="aes256-sha256-ecp384,aes256-sha256-ecp256,aes128-sha256-ecp256,aes256-sha256-modp2048,aes128-sha256-modp2048,aes256-sha1-modp2048,aes128-sha1-modp2048,aes256-sha256-modp1024,3des-sha1-modp1024"
ESP_PROPOSAL="aes256-sha256,aes128-sha256,aes256-sha1,aes128-sha1"

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
# Do NOT change the global FORWARD policy — it weakens Docker/container isolation.
# Use a scoped rule instead: only forward traffic from our VPN subnet to WAN.
WAN_IF="$(ip route show default 2>/dev/null | awk '{print $5; exit}')"
if [ -n "$WAN_IF" ]; then
    iptables -C FORWARD -i eth+ -o "$WAN_IF" -s 10.10.10.0/24 -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD 1 -i eth+ -o "$WAN_IF" -s 10.10.10.0/24 -j ACCEPT
    iptables -t nat -C POSTROUTING -s 10.10.10.0/24 -o "$WAN_IF" -j MASQUERADE 2>/dev/null || \
        iptables -t nat -A POSTROUTING -s 10.10.10.0/24 -o "$WAN_IF" -j MASQUERADE
else
    iptables -C FORWARD -i eth+ -o lo -s 10.10.10.0/24 -j ACCEPT 2>/dev/null || \
        iptables -I FORWARD 1 -i eth+ -o lo -s 10.10.10.0/24 -j ACCEPT
    iptables -t nat -C POSTROUTING -s 10.10.10.0/24 -j MASQUERADE 2>/dev/null || \
        iptables -t nat -A POSTROUTING -s 10.10.10.0/24 -j MASQUERADE
fi
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow 500/udp > /dev/null 2>&1 || true
    ufw allow 4500/udp > /dev/null 2>&1 || true
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
