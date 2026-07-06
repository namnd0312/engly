#!/usr/bin/env bash
# Build avatar-resize.zip for the python3.12 Lambda runtime.
#
# Pillow is a C extension: wheels must match the Lambda container's OS/arch,
# NOT the macOS host. We install inside the AWS SAM build image for the target
# arch and smoke-test the import in that same image before zipping.
#
# Usage:  ./build.sh            (arm64 default — Apple Silicon runs arm64 containers natively)
#         LAMBDA_ARCH=x86_64 ./build.sh
set -euo pipefail

ARCH="${LAMBDA_ARCH:-arm64}"
cd "$(dirname "$0")"

rm -rf package avatar-resize.zip

echo "==> Installing dependencies for linux/${ARCH} (python3.12)"
docker run --rm --platform "linux/${ARCH}" \
  -v "$PWD":/var/task -w /var/task \
  public.ecr.aws/sam/build-python3.12 \
  pip install --quiet -r requirements.txt -t package/

cp handler.py package/

echo "==> Smoke test: import PIL inside the target image"
docker run --rm --platform "linux/${ARCH}" \
  -v "$PWD/package":/opt/pkg \
  public.ecr.aws/sam/build-python3.12 \
  python -c "import sys; sys.path.insert(0, '/opt/pkg'); import PIL; print('Pillow', PIL.__version__, 'OK')"

echo "==> Zipping"
(cd package && zip -qr ../avatar-resize.zip .)
echo "==> Built $(pwd)/avatar-resize.zip ($(du -h avatar-resize.zip | cut -f1))"
