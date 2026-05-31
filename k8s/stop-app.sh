#!/usr/bin/env bash
#
# stop-app.sh - Stop Engly app in Kubernetes by scaling deployments to 0 replicas.
#
# Scaling to 0 (instead of `kubectl delete`) keeps the Deployments, Services,
# PVC and Secret intact, so `./start-app.sh` (or scaling back up) restores the
# app without losing data or config.
#
# Usage:
#   ./stop-app.sh           # stop the app (engly-be + engly-fe)
#   ./stop-app.sh --all     # also stop the postgres database
#   ./stop-app.sh -n myns   # override namespace (default: engly)
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

targets=("${APP_DEPLOYMENTS[@]}")
$INCLUDE_DB && targets+=("${DB_DEPLOYMENTS[@]}")

echo "Stopping deployments in namespace '$NAMESPACE': ${targets[*]}"

for dep in "${targets[@]}"; do
  if kubectl -n "$NAMESPACE" get deployment "$dep" >/dev/null 2>&1; then
    echo "  -> scaling $dep to 0"
    kubectl -n "$NAMESPACE" scale deployment "$dep" --replicas=0
  else
    echo "  -> skip $dep (not found)"
  fi
done

echo "Waiting for pods to terminate..."
for dep in "${targets[@]}"; do
  kubectl -n "$NAMESPACE" rollout status deployment "$dep" --timeout=120s 2>/dev/null || true
done

echo "Done. Current state:"
kubectl -n "$NAMESPACE" get deployments
