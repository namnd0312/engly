"""Avatar resize Lambda.

Triggered by S3 ObjectCreated events on keys matching avatars/{userId}/{uploadId}/original.
Writes square cover-cropped WebP variants (256px, 64px) next to the original:
avatars/{userId}/{uploadId}/256.webp and .../64.webp.

Derived keys never end in "original", so the S3 notification suffix filter
("original") can never re-trigger this function on its own output.

AWS_ENDPOINT_URL is set when running against a local emulator (Floci); on real
AWS it is unset and boto3 uses the default endpoint.
"""

import io
import logging
import os
import urllib.parse

import boto3
from PIL import Image, ImageOps

SIZES = (256, 64)
WEBP_QUALITY = 85

# INFO-level logs land in CloudWatch Logs (/aws/lambda/engly-avatar-resize) — both on real AWS
# and on the Floci emulator — so each invocation's input key + outputs are observable.
logger = logging.getLogger()
logger.setLevel(logging.INFO)

s3 = boto3.client("s3", endpoint_url=os.environ.get("AWS_ENDPOINT_URL"))


def _resize_to_webp(data: bytes, size: int) -> bytes:
    """Square cover-crop `data` to size x size and encode as WebP."""
    img = Image.open(io.BytesIO(data))
    # Respect EXIF orientation (phone photos) before any cropping.
    img = ImageOps.exif_transpose(img)
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA")
    img = ImageOps.fit(img, (size, size))
    out = io.BytesIO()
    img.save(out, format="WEBP", quality=WEBP_QUALITY)
    return out.getvalue()


def lambda_handler(event, context):
    records = event.get("Records", [])
    logger.info("Invoked with %d S3 record(s)", len(records))
    processed = []
    for record in records:
        bucket = record["s3"]["bucket"]["name"]
        # Event keys are URL-encoded (spaces become '+', etc.).
        key = urllib.parse.unquote_plus(record["s3"]["object"]["key"])

        # Defense-in-depth against recursion if the notification filter is misconfigured.
        if not key.endswith("/original"):
            logger.info("Skipping non-original key: %s", key)
            continue

        data = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
        prefix = key.rsplit("/", 1)[0]
        logger.info("Resizing s3://%s/%s (%d bytes)", bucket, key, len(data))

        for size in SIZES:
            derived_key = f"{prefix}/{size}.webp"
            body = _resize_to_webp(data, size)
            s3.put_object(
                Bucket=bucket,
                Key=derived_key,
                Body=body,
                ContentType="image/webp",
                # Keys are content-addressed by uploadId; variants never change once written.
                CacheControl="public, max-age=31536000, immutable",
            )
            logger.info("Wrote %s (%d bytes)", derived_key, len(body))
            processed.append(derived_key)

    logger.info("Done — produced %d variant(s)", len(processed))
    return {"processed": processed}
