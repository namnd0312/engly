#!/usr/bin/env bash
# One-time (idempotent) setup of avatar resources on the local Floci AWS emulator:
#   1. S3 bucket engly-avatars (+ CORS for the SPA origins)
#   2. avatar-resize Lambda (python3.12 + Pillow zip, built via ../lambda/avatar-resize/build.sh)
#   3. S3 ObjectCreated notification (prefix avatars/, suffix "original") -> Lambda
#
# Prereqs: `docker compose up -d floci` at repo root; aws CLI; Docker running.
# Re-run any time — safe if resources already exist (Floci may not persist across restarts).
set -euo pipefail

ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
# Endpoint as seen FROM INSIDE a Lambda container: Floci spawns them on the compose
# network (engly_default), where "localhost" is the Lambda container itself. Use the
# floci service DNS name instead.
LAMBDA_ENDPOINT="${FLOCI_LAMBDA_ENDPOINT:-http://floci:4566}"
BUCKET="engly-avatars"
FN="engly-avatar-resize"
REGION="us-east-1"
ARCH="${LAMBDA_ARCH:-arm64}"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION="$REGION"

aws() { command aws --endpoint-url "$ENDPOINT" "$@"; }

cd "$(dirname "$0")"

echo "==> Waiting for Floci at $ENDPOINT"
for i in $(seq 1 30); do
  if curl -sf -o /dev/null "$ENDPOINT" || [ "$(curl -s -o /dev/null -w '%{http_code}' "$ENDPOINT")" != "000" ]; then
    break
  fi
  [ "$i" = 30 ] && { echo "Floci not reachable at $ENDPOINT"; exit 1; }
  sleep 1
done

echo "==> Creating bucket $BUCKET"
aws s3api create-bucket --bucket "$BUCKET" 2>/dev/null || echo "    (bucket already exists)"

echo "==> Applying bucket CORS (SPA origins :30090 k8s, :4200 ng serve)"
aws s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:30090", "http://localhost:4200"],
    "AllowedMethods": ["PUT", "GET"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"]
  }]
}'

echo "==> Building Lambda zip"
bash ../lambda/avatar-resize/build.sh

ZIP="fileb://../lambda/avatar-resize/avatar-resize.zip"
ROLE="arn:aws:iam::000000000000:role/lambda-role"  # emulator accepts any role ARN

echo "==> Creating/updating Lambda $FN"
if aws lambda get-function --function-name "$FN" >/dev/null 2>&1; then
  aws lambda update-function-code --function-name "$FN" --zip-file "$ZIP" >/dev/null
  echo "    (updated existing function code)"
else
  # No --architectures flag: older aws CLIs lack it, and the emulator runs Lambda
  # containers on the host's native arch anyway (matches the LAMBDA_ARCH zip build).
  aws lambda create-function \
    --function-name "$FN" \
    --runtime python3.12 \
    --handler handler.lambda_handler \
    --zip-file "$ZIP" \
    --role "$ROLE" \
    --timeout 30 \
    --memory-size 512 \
    --environment "Variables={AWS_ENDPOINT_URL=$LAMBDA_ENDPOINT}" >/dev/null
fi

# Keep env in sync on re-runs too (create-function path above only runs the first time).
aws lambda update-function-configuration --function-name "$FN" \
  --environment "Variables={AWS_ENDPOINT_URL=$LAMBDA_ENDPOINT}" >/dev/null

FN_ARN=$(aws lambda get-function --function-name "$FN" --query 'Configuration.FunctionArn' --output text)

echo "==> Allowing S3 to invoke $FN"
aws lambda add-permission \
  --function-name "$FN" \
  --statement-id s3invoke \
  --action lambda:InvokeFunction \
  --principal s3.amazonaws.com \
  --source-arn "arn:aws:s3:::$BUCKET" 2>/dev/null || echo "    (permission already present)"

echo "==> Wiring S3 ObjectCreated notification -> $FN_ARN"
aws s3api put-bucket-notification-configuration --bucket "$BUCKET" --notification-configuration '{
  "LambdaFunctionConfigurations": [{
    "LambdaFunctionArn": "'"$FN_ARN"'",
    "Events": ["s3:ObjectCreated:*"],
    "Filter": {"Key": {"FilterRules": [
      {"Name": "prefix", "Value": "avatars/"},
      {"Name": "suffix", "Value": "original"}
    ]}}
  }]
}'

echo "==> Done. Smoke test:"
echo "    aws --endpoint-url $ENDPOINT s3 cp photo.jpg s3://$BUCKET/avatars/u1/x1/original --content-type image/jpeg"
echo "    aws --endpoint-url $ENDPOINT s3 ls s3://$BUCKET/avatars/u1/x1/   # expect 256.webp + 64.webp"
