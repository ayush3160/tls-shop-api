#!/usr/bin/env bash
#
# pf-and-hit.sh — port-forward the deployed API, then exercise every route.
#
# Wraps seed-and-hit.sh for a cluster deployment: opens a port-forward to the
# tls-shop-api Service, waits for it to answer, runs the suite through it, and
# tears the tunnel down on the way out (even on Ctrl+C or failure).
#
# Exists because the NodePort (30090) has no host mapping on this kind cluster,
# so the README's `curl localhost:30090` can't work — the forward is the way in.
#
# Usage:
#   ./scripts/pf-and-hit.sh                    # auto-picks a free local port
#   LOCAL_PORT=8095 ./scripts/pf-and-hit.sh    # pin the local port
#   KUBE_CONTEXT=other NAMESPACE=ns ./scripts/pf-and-hit.sh
#   ./scripts/pf-and-hit.sh --health-only      # just hit the health endpoints
#   ./scripts/pf-and-hit.sh --fresh           # wipe the DB first, so the suite can pass again
#
# NOTE: seed-and-hit.sh is single-shot — it seeds fixed slugs/SKUs/emails, so a
# second run against the same data 409s on every POST and cascades into /null
# requests. Use --fresh for a repeatable 58/58.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Defaults target the local kind deployment. Deliberately NOT the current
# kubectl context: this script writes data, and must never guess its way
# into a real cluster.
KUBE_CONTEXT="${KUBE_CONTEXT:-kind-kind}"
NAMESPACE="${NAMESPACE:-tls-shop}"
SERVICE="${SERVICE:-tls-shop-api}"
SERVICE_PORT="${SERVICE_PORT:-80}"
READY_TIMEOUT="${READY_TIMEOUT:-60}"

HEALTH_ONLY=0
FRESH=0
for arg in "$@"; do
  case "$arg" in
    --health-only) HEALTH_ONLY=1 ;;
    --fresh)       FRESH=1 ;;
    -h|--help)     sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'; exit 0 ;;
    *)             echo "❌ unknown argument: $arg (try --help)"; exit 1 ;;
  esac
done

need() { command -v "$1" >/dev/null 2>&1 || { echo "❌ $1 is required"; exit 1; }; }
need kubectl
need curl
[[ $HEALTH_ONLY -eq 1 ]] || need jq

port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

# Pin a port if asked, otherwise find one that's actually free. Ports in the
# NodePort range are avoided — VS Code holds several of them on loopback here.
if [[ -n "${LOCAL_PORT:-}" ]]; then
  if port_busy "$LOCAL_PORT"; then
    echo "❌ LOCAL_PORT=$LOCAL_PORT is already in use:"
    lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN | tail -n +2 | sed 's/^/     /'
    exit 1
  fi
else
  for p in $(seq 8091 8110); do
    port_busy "$p" || { LOCAL_PORT="$p"; break; }
  done
  [[ -n "${LOCAL_PORT:-}" ]] || { echo "❌ no free local port in 8091-8110"; exit 1; }
fi

BASE_URL="http://localhost:${LOCAL_PORT}"

echo "▶ context   : $KUBE_CONTEXT"
echo "▶ namespace : $NAMESPACE"
echo "▶ target    : svc/$SERVICE:$SERVICE_PORT  ->  $BASE_URL"

# Fail early with a clear reason rather than a port-forward timeout.
if ! kubectl --context="$KUBE_CONTEXT" -n "$NAMESPACE" get svc "$SERVICE" >/dev/null 2>&1; then
  echo "❌ svc/$SERVICE not found in namespace '$NAMESPACE' (context '$KUBE_CONTEXT')."
  echo "   Deploy it first — see k8s/deploy.sh or the README."
  exit 1
fi

if [[ $FRESH -eq 1 ]]; then
  echo "▶ --fresh: restarting deploy/mongodb to wipe the database (emptyDir storage)…"
  kubectl --context="$KUBE_CONTEXT" -n "$NAMESPACE" rollout restart deploy/mongodb || exit 1
  kubectl --context="$KUBE_CONTEXT" -n "$NAMESPACE" rollout status deploy/mongodb --timeout=180s || exit 1
fi

PF_PID=""
PF_LOG="$(mktemp)"
cleanup() {
  local code=$?
  if [[ -n "$PF_PID" ]] && kill -0 "$PF_PID" 2>/dev/null; then
    echo "▶ closing port-forward (pid $PF_PID)…"
    kill "$PF_PID" 2>/dev/null
    wait "$PF_PID" 2>/dev/null
  fi
  rm -f "$PF_LOG"
  exit $code
}
trap cleanup EXIT INT TERM

echo "▶ opening port-forward…"
kubectl --context="$KUBE_CONTEXT" -n "$NAMESPACE" \
  port-forward "svc/$SERVICE" "${LOCAL_PORT}:${SERVICE_PORT}" --address 127.0.0.1 \
  >"$PF_LOG" 2>&1 &
PF_PID=$!

# Wait on the app answering, not just the socket being open — the tunnel binds
# before the pod is necessarily serving.
ready=0
for ((i = 1; i <= READY_TIMEOUT; i++)); do
  if ! kill -0 "$PF_PID" 2>/dev/null; then
    echo "❌ port-forward died:"; sed 's/^/     /' "$PF_LOG"; exit 1
  fi
  if curl -sf -o /dev/null "${BASE_URL}/health" 2>/dev/null; then
    ready=1; echo "✅ API answering after ${i}s"; break
  fi
  sleep 1
done
if [[ $ready -ne 1 ]]; then
  echo "❌ no response from ${BASE_URL}/health within ${READY_TIMEOUT}s"
  sed 's/^/     /' "$PF_LOG"
  exit 1
fi

echo
echo "▶ health"
curl -s "${BASE_URL}/health"; echo
if command -v jq >/dev/null 2>&1; then
  curl -s "${BASE_URL}/api/health/db" | jq -c .
  # The whole point of this app: confirm the Mongo link is really TLS.
  tls="$(curl -s "${BASE_URL}/api/health/db" | jq -r '.tls // "unknown"')"
  [[ "$tls" == "true" ]] && echo "✅ Mongo link is TLS" || echo "⚠️  Mongo link reports tls=$tls"
else
  curl -s "${BASE_URL}/api/health/db"; echo
fi

if [[ $HEALTH_ONLY -eq 1 ]]; then
  echo; echo "✓ health-only mode — skipping the route suite."
  exit 0
fi

echo
echo "▶ running seed-and-hit.sh against $BASE_URL"
echo "  (this WRITES data: creates users, products, orders; deletes a few)"
echo
BASE_URL="$BASE_URL" "$ROOT/scripts/seed-and-hit.sh"
exit $?
