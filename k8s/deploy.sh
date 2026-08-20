#!/usr/bin/env bash
#
# deploy.sh — build the image, load it into kind, and apply the manifests.
#
#   ./k8s/deploy.sh                 # cluster name defaults to "kind"
#   KIND_CLUSTER=mycluster ./k8s/deploy.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
CLUSTER="${KIND_CLUSTER:-kind}"
IMAGE="tls-shop-api:1.0.0"

[[ -f certs/mongodb.pem && -f certs/truststore.p12 ]] || {
  echo "certs missing — running gen-certs.sh"; ./scripts/gen-certs.sh; }

echo "▶ Building jar…"
mvn -q -o clean package -DskipTests

echo "▶ Building image $IMAGE…"
docker build -t "$IMAGE" .

echo "▶ Loading image into kind cluster '$CLUSTER'…"
kind load docker-image "$IMAGE" --name "$CLUSTER"

echo "▶ Creating namespace + TLS secrets from certs/…"
kubectl create namespace tls-shop --dry-run=client -o yaml | kubectl apply -f -
kubectl -n tls-shop create secret generic mongo-certs \
  --from-file=ca.crt=certs/ca.crt --from-file=mongodb.pem=certs/mongodb.pem \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n tls-shop create secret generic app-truststore \
  --from-file=truststore.p12=certs/truststore.p12 \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n tls-shop create secret generic app-secrets \
  --from-literal=MONGO_TLS_TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-changeit}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "▶ Applying manifests…"
kubectl apply -k k8s/

echo "▶ Waiting for rollouts…"
kubectl -n tls-shop rollout status deploy/mongodb --timeout=120s
kubectl -n tls-shop rollout status deploy/tls-shop-api --timeout=180s

echo
echo "✓ Deployed. API reachable at http://localhost:30090"
echo "    curl -s http://localhost:30090/api/health/db | jq"
