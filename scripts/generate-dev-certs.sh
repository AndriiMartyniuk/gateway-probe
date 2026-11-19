#!/usr/bin/env bash
# Generates development-only TLS material for the CoAP-over-TLS endpoint:
#   ca.pem / ca.key           self-signed dev CA (EC P-256)
#   server.p12                server key + chain, alias "server", password "changeit"; SAN localhost, 127.0.0.1,
#                             probe, gateway-probe (Docker service names of the experiment stand and compose)
#   <endpoint>.der/.key.der   client certificate (CN = LwM2M endpoint name) + PKCS#8 key, for test clients
# Usage: scripts/generate-dev-certs.sh [output-dir] [client-endpoint ...]
set -euo pipefail

OUT=${1:-certs}
shift || true
CLIENTS=("$@")
[ ${#CLIENTS[@]} -eq 0 ] && CLIENTS=(dev-device-1)
PASS=changeit
DAYS=825

mkdir -p "$OUT"
cd "$OUT"

openssl ecparam -name prime256v1 -genkey -noout -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days 3650 -subj "/CN=gateway-probe-dev-ca" -out ca.pem

openssl ecparam -name prime256v1 -genkey -noout -out server.key
openssl req -new -key server.key -subj "/CN=localhost" -out server.csr
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:probe,DNS:gateway-probe\nextendedKeyUsage=serverAuth\n' > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -sha256 -days $DAYS \
  -extfile server.ext -out server.pem
openssl pkcs12 -export -inkey server.key -in server.pem -certfile ca.pem -name server \
  -passout pass:$PASS -out server.p12
openssl x509 -in server.pem -outform DER -out server.der

for ep in "${CLIENTS[@]}"; do
  openssl ecparam -name prime256v1 -genkey -noout -out "$ep.key"
  openssl req -new -key "$ep.key" -subj "/CN=$ep" -out "$ep.csr"
  printf 'extendedKeyUsage=clientAuth\n' > client.ext
  openssl x509 -req -in "$ep.csr" -CA ca.pem -CAkey ca.key -CAcreateserial -sha256 -days $DAYS \
    -extfile client.ext -out "$ep.pem"
  openssl x509 -in "$ep.pem" -outform DER -out "$ep.der"
  openssl pkcs8 -topk8 -nocrypt -in "$ep.key" -outform DER -out "$ep.key.der"
done

rm -f ./*.csr ./*.ext ./*.srl
echo "Dev TLS material written to $(pwd)"
