#!/usr/bin/env bash
# Generates the self-signed certificate used by the QA HTTPS server.
# The certificate is intentionally untrusted: it exercises Half-Browser's
# certificate interstitial, "trust once" and "always trust" flows.
set -euo pipefail
cd "$(dirname "$0")"
HOST_IP="${1:-192.168.0.165}"
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 365 \
  -keyout key.pem -out cert.pem \
  -subj "/C=JP/ST=Tokyo/L=Tokyo/O=Half-Browser QA/OU=Local/CN=${HOST_IP}" \
  -addext "subjectAltName=IP:${HOST_IP},IP:127.0.0.1,DNS:localhost"
echo "Wrote cert.pem / key.pem for ${HOST_IP}"
openssl x509 -in cert.pem -noout -subject -issuer -dates -fingerprint -sha256
