package com.engly.identity.infrastructure;

import com.engly.identity.config.AwsProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Instant;
import java.util.Optional;

/**
 * The single S3-touching adapter for avatar storage — everything above this class (services,
 * controllers, tests) depends on this seam, so tests mock it and never need a live emulator.
 *
 * <p>Presigned URLs are minted against the browser-facing endpoint (see {@code S3ClientConfig});
 * {@code exists}/{@code contentTypeOf} run against the server-side endpoint.
 */
@Component
public class S3AvatarStorage {

    /** A presigned PUT the browser can upload to until {@code expiresAt}. */
    public record PresignedUpload(String url, String objectKey, Instant expiresAt) {}

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AwsProperties props;

    public S3AvatarStorage(S3Client s3, S3Presigner presigner, AwsProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    /** Presign a PUT for {@code objectKey}; the Content-Type is part of the signature. */
    public PresignedUpload presignUpload(String objectKey, String contentType) {
        var put = PutObjectRequest.builder()
                .bucket(props.s3().bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(props.s3().uploadUrlTtl())
                .putObjectRequest(put)
                .build());
        return new PresignedUpload(presigned.url().toString(), objectKey, presigned.expiration());
    }

    /** Presign a GET for serving an avatar to the browser. */
    public String presignDownload(String objectKey) {
        var get = GetObjectRequest.builder()
                .bucket(props.s3().bucket())
                .key(objectKey)
                .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(props.s3().downloadUrlTtl())
                .getObjectRequest(get)
                .build()).url().toString();
    }

    /** True if the object exists in the avatar bucket. */
    public boolean exists(String objectKey) {
        return head(objectKey).isPresent();
    }

    /** Stored Content-Type of the object, or empty if it does not exist. */
    public Optional<String> contentTypeOf(String objectKey) {
        return head(objectKey).map(HeadObjectResponse::contentType);
    }

    private Optional<HeadObjectResponse> head(String objectKey) {
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.s3().bucket())
                    .key(objectKey)
                    .build()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }
}
