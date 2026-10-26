package com.engly.identity.application;

import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.domain.InvalidAvatarException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.S3AvatarStorage;
import com.engly.identity.infrastructure.S3AvatarStorage.PresignedUpload;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Self-service profile: display-name edits and the avatar upload lifecycle.
 *
 * <p>Avatar flow: {@link #createAvatarUploadUrl} mints a presigned PUT for a key the BACKEND
 * generates (client never chooses keys) → browser uploads directly to S3 → {@link #confirmAvatar}
 * re-validates ownership/shape/existence and persists the key. The resize Lambda then writes
 * 256/64 WebP variants next to the original; {@link #toUserInfo} serves those when present and
 * falls back to the original until they appear.
 */
@Service
public class ProfileService {

    /** Only browser-renderable raster formats; signed into the PUT so S3 enforces it. */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    /** avatars/{userId}/{uploadId}/original — both ids are UUIDs. */
    private static final Pattern KEY_SHAPE =
            Pattern.compile("^avatars/[0-9a-f-]{36}/[0-9a-f-]{36}/original$");

    private final UserAccountRepository users;
    private final S3AvatarStorage storage;

    public ProfileService(UserAccountRepository users, S3AvatarStorage storage) {
        this.users = users;
        this.storage = storage;
    }

    @Transactional
    public UserAccount updateDisplayName(String sub, String displayName) {
        UserAccount user = requireUser(sub);
        user.updateDisplayName(displayName.trim());
        return user;
    }

    @Transactional(readOnly = true)
    public PresignedUpload createAvatarUploadUrl(String sub, String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidAvatarException(
                    "Unsupported avatar content type; allowed: " + ALLOWED_CONTENT_TYPES);
        }
        UserAccount user = requireUser(sub);
        String objectKey = "avatars/" + user.getId() + "/" + UUID.randomUUID() + "/original";
        return storage.presignUpload(objectKey, contentType.toLowerCase());
    }

    @Transactional
    public UserAccount confirmAvatar(String sub, String objectKey) {
        UserAccount user = requireUser(sub);
        // Ownership: the key must sit under the caller's own prefix (backend generated it there).
        if (!objectKey.startsWith("avatars/" + user.getId() + "/") || !KEY_SHAPE.matcher(objectKey).matches()) {
            throw new InvalidAvatarException("Object key does not belong to the current user");
        }
        // Existence + the stored Content-Type must still be an allowed image type.
        String storedType = storage.contentTypeOf(objectKey)
                .orElseThrow(() -> new InvalidAvatarException("No uploaded object found for this key"));
        if (!ALLOWED_CONTENT_TYPES.contains(storedType.toLowerCase())) {
            throw new InvalidAvatarException("Uploaded object is not an allowed image type");
        }
        user.changeAvatar(objectKey);
        return user;
    }

    /**
     * Identity projection with presigned avatar URLs. One HeadObject decides whether the resized
     * variants exist yet; until then the original serves both slots (graceful pre-Lambda window).
     */
    @Transactional(readOnly = true)
    public UserInfoResponse toUserInfo(UserAccount user, boolean isAdmin, boolean mfaEnrolled, boolean mfaVerified) {
        String avatarKey = user.getAvatarKey();
        if (avatarKey == null) {
            return new UserInfoResponse(user.getEmail(), user.getDisplayName(),
                    isAdmin, null, null, mfaEnrolled, mfaVerified);
        }
        String base = avatarKey.substring(0, avatarKey.length() - "/original".length());
        String avatarUrl;
        String thumbUrl;
        if (storage.exists(base + "/256.webp")) {
            avatarUrl = storage.presignDownload(base + "/256.webp");
            thumbUrl = storage.presignDownload(base + "/64.webp");
        } else {
            String original = storage.presignDownload(avatarKey);
            avatarUrl = original;
            thumbUrl = original;
        }
        return new UserInfoResponse(user.getEmail(), user.getDisplayName(),
                isAdmin, avatarUrl, thumbUrl, mfaEnrolled, mfaVerified);
    }

    private UserAccount requireUser(String sub) {
        return users.findBySub(sub)
                .orElseThrow(() -> new InvalidAvatarException("No local account for current user"));
    }
}
