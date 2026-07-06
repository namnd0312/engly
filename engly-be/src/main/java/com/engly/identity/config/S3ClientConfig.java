package com.engly.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 client wiring for avatar storage.
 *
 * <p>Two beans with a deliberate endpoint split (mirrors the OPEN-GATE OIDC precedent of
 * browser-facing vs server-side URIs in k8s):
 * <ul>
 *   <li>{@link S3Client} — the backend's own calls (HeadObject at confirm/serve time). Uses
 *       {@code aws.s3.endpoint}, which must be reachable FROM the backend process.</li>
 *   <li>{@link S3Presigner} — mints presigned PUT/GET URLs consumed by the BROWSER. Uses
 *       {@code aws.s3.public-endpoint}: the signature is host-specific, so this must be exactly
 *       the origin the browser hits. The presigner performs no network I/O, so pointing it at a
 *       host unreachable from the backend is safe.</li>
 * </ul>
 *
 * <p>Path-style access is mandatory with a local emulator: virtual-host style would address
 * {@code engly-avatars.localhost} and break both DNS and signature stability.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(AwsProperties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(pathStyle());
        if (hasText(props.s3().endpoint())) {
            builder.endpointOverride(URI.create(props.s3().endpoint()));
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(AwsProperties props) {
        var builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(pathStyle());
        if (hasText(props.s3().publicEndpoint())) {
            builder.endpointOverride(URI.create(props.s3().publicEndpoint()));
        }
        return builder.build();
    }

    private static S3Configuration pathStyle() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }

    /** Static creds when configured (emulator/test); otherwise the default chain (real AWS). */
    private static AwsCredentialsProvider credentials(AwsProperties props) {
        if (hasText(props.accessKey()) && hasText(props.secretKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
