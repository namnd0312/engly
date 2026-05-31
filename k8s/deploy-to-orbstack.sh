#!/usr/bin/env sh
# Build both Engly images into OrbStack's shared image store, then apply the k8s manifests.
# OrbStack's Docker engine and its Kubernetes cluster share an image store, so locally
# built images (non-:latest tag + imagePullPolicy IfNotPresent) need no registry push.
#
# Usage:  sh k8s/deploy-to-orbstack.sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Ensuring kubectl context = orbstack"
kubectl config use-context orbstack

echo "==> Building backend image (engly-be:local)"
docker build -t engly-be:local ./engly-be

echo "==> Building frontend image (engly-fe:local)"
docker build -t engly-fe:local ./engly-fe

echo "==> Applying k8s manifests"
kubectl apply -f k8s/

echo "==> Waiting for rollouts"
kubectl -n engly rollout status deploy/postgres   --timeout=120s
kubectl -n engly rollout status deploy/engly-be    --timeout=180s
kubectl -n engly rollout status deploy/engly-fe    --timeout=120s

echo "==> Done. Services:"
kubectl -n engly get svc
