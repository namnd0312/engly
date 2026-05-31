#!/usr/bin/env bash
#
# start-app.sh - Start Engly app in Kubernetes by scaling deployments to 1 replica.
#
# Counterpart to ./stop-app.sh. Brings deployments that were scaled to 0 back up.
# The database is started first (when --all) so the backend can connect on boot.
#
# Usage:
#   ./start-app.sh           # start the app (engly-be + engly-fe)
#   ./start-app.sh --all     # also start the postgres database first
#   ./start-app.sh -n myns   # override namespace (default: engly)
#
set -euo pipefail

NAMESPACE="engly"
APP_DEPLOYMENTS=("engly-be" "engly-fe")
DB_DEPLOYMENTS=("postgres")
INCLUDE_DB=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all|-a)       INCLUDE_DB=true; shift ;;
    -n|--namespace) NAMESPACE="$2"; shift 2 ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^#//'
      exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1 ;;
  esac
done

command -v kubectl >/dev/null 2>&1 || { echo "Error: kubectl not found in PATH" >&2; exit 1; }

# DB first so the backend has something to connect to on startup.
targets=()
$INCLUDE_DB && targets+=("${DB_DEPLOYMENTS[@]}")
targets+=("${APP_DEPLOYMENTS[@]}")

echo "Starting deployments in namespace '$NAMESPACE': ${targets[*]}"

for dep in "${targets[@]}"; do
  if kubectl -n "$NAMESPACE" get deployment "$dep" >/dev/null 2>&1; then
    echo "  -> scaling $dep to 1"
    kubectl -n "$NAMESPACE" scale deployment "$dep" --replicas=1
  else
    echo "  -> skip $dep (not found)"
  fi
done

echo "Waiting for pods to become ready..."
for dep in "${targets[@]}"; do
  kubectl -n "$NAMESPACE" rollout status deployment "$dep" --timeout=180s 2>/dev/null || true
done

echo "Done. Current state:"
kubectl -n "$NAMESPACE" get deployments
