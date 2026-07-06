package com.engly.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AWS/S3 settings for avatar storage (bound from {@code aws.*}).
 *
 * <p>Defaults target the local emulator (Floci at {@code localhost:4566}, creds test/test).
 * On real AWS leave {@code endpoint}/{@code publicEndpoint} blank so the SDK uses its default
 * endpoints, and leave the keys blank to fall back to the default credential provider chain.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String region,
        String accessKey,
        String secretKey,
        S3 s3) {

    public AwsProperties {
        if (region == null || region.isBlank()) region = "us-east-1";
        if (s3 == null) s3 = new S3(null, null, null, null, null);
    }

    /**
     * S3 avatar-bucket settings. {@code endpoint} is what the BACKEND calls (in-cluster/host
     * route); {@code publicEndpoint} is what the BROWSER hits — presigned URLs are signed
     * per-host, so it must exactly match the browser-visible origin.
     */
    public record S3(String bucket, String endpoint, String publicEndpoint,
                     Duration uploadUrlTtl, Duration downloadUrlTtl) {
        public S3 {
            if (bucket == null || bucket.isBlank()) bucket = "engly-avatars";
            if (uploadUrlTtl == null) uploadUrlTtl = Duration.ofMinutes(15);
            if (downloadUrlTtl == null) downloadUrlTtl = Duration.ofHours(1);
        }
    }
}
