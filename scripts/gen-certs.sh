#!/usr/bin/env bash
#
# gen-certs.sh — generates a local CA, a MongoDB server cert signed by it, and a
# Java truststore holding the CA. This is what makes the app<->DB link TLS but
# NOT pinned: the app trusts the CA, so the server leaf cert can be regenerated
# any time without touching the client.
#
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/certs"
mkdir -p "$DIR"; cd "$DIR"

STOREPASS="${TRUSTSTORE_PASSWORD:-changeit}"

echo "▶ Generating CA…"
openssl genrsa -out ca.key 4096 2>/dev/null
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -subj "/CN=tls-shop-local-CA/O=keploy-demo" -out ca.crt

echo "▶ Generating MongoDB server cert (SAN=localhost,mongodb)…"
openssl genrsa -out server.key 4096 2>/dev/null
cat > server.cnf <<'EOF'
[req]
distinguished_name = dn
req_extensions = ext
prompt = no
[dn]
CN = localhost
O = keploy-demo
[ext]
subjectAltName = @alt
[alt]
DNS.1 = localhost
DNS.2 = mongodb
IP.1  = 127.0.0.1
EOF
openssl req -new -key server.key -out server.csr -config server.cnf
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 825 -sha256 -extfile server.cnf -extensions ext

# MongoDB wants key+cert concatenated in one PEM.
cat server.key server.crt > mongodb.pem
# 644 so the non-root 'mongodb' user inside the container can read the mounted PEM.
# (Local demo only — a real deployment would tighten this and match the runtime uid.)
chmod 644 mongodb.pem

echo "▶ Building Java truststore (contains the CA only, not the leaf cert)…"
rm -f truststore.p12
keytool -importcert -noprompt -trustcacerts \
  -alias tls-shop-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$STOREPASS"

echo
echo "✓ Wrote to $DIR :"
echo "    ca.crt          — the CA (public)"
echo "    mongodb.pem     — server key+cert for mongod --tlsCertificateKeyFile"
echo "    truststore.p12  — CA truststore for the Java app (pass: $STOREPASS)"
echo
echo "Run the app against it with:"
echo "    export MONGODB_URI='mongodb://localhost:27017/shopdb?tls=true'"
echo "    export MONGO_TLS_TRUSTSTORE=$DIR/truststore.p12"
echo "    export MONGO_TLS_TRUSTSTORE_PASSWORD=$STOREPASS"
