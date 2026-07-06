#!/usr/bin/env bash
# Observe the avatar-resize Lambda on the local Floci emulator.
#
# Usage:
#   ./watch-lambda.sh                 # follow the Lambda's INFO logs live (upload to see it fire)
#   ./watch-lambda.sh ls <prefix>     # list an avatar prefix, e.g. avatars/<userId>/<uploadId>
#   ./watch-lambda.sh logs            # dump the recent Lambda INFO logs once
#
# NOTE: on the Floci emulator the Lambda's stdout is streamed into the FLOCI CONTAINER LOG
# (docker logs engly-floci), not the CloudWatch Logs API — so this script reads docker logs.
# On real AWS the same handler logs land in CloudWatch (/aws/lambda/engly-avatar-resize).
#
# Prereqs: Floci running (docker compose --profile floci up -d), aws CLI.
set -euo pipefail

ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
BUCKET="engly-avatars"
FN="engly-avatar-resize"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
aws() { command aws --endpoint-url "$ENDPOINT" "$@"; }

# Lambda stdout arrives in Floci's container log tagged "[lambda:<fn>]"; keep that + launch lines.
LOG_FILTER="lambda:${FN}|Launching container for function: ${FN}"

case "${1:-watch}" in
  ls)
    prefix="${2:?usage: watch-lambda.sh ls avatars/<userId>/<uploadId>}"
    aws s3 ls "s3://${BUCKET}/${prefix%/}/"
    ;;
  logs)
    echo "== Recent ${FN} logs (from Floci container) =="
    docker logs --since 10m engly-floci 2>&1 | grep -iE "$LOG_FILTER" | tail -40 \
      || echo "(nothing yet — upload an avatar first)"
    ;;
  watch)
    echo "==> Following ${FN} activity (Ctrl-C to stop). Upload an avatar to see it fire."
    echo ""
    docker logs -f --since 5s engly-floci 2>&1 | grep --line-buffered -iE "$LOG_FILTER" || true
    ;;
  *)
    echo "usage: watch-lambda.sh [watch|ls <prefix>|logs]"; exit 1;;
esac
