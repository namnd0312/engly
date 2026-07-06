package com.engly.identity.application;

import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.domain.InvalidAvatarException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.S3AvatarStorage;
import com.engly.identity.infrastructure.S3AvatarStorage.PresignedUpload;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileService}: display-name edits and the avatar upload lifecycle
 * (content-type allowlist, backend-generated key shape, ownership checks at confirm, and the
 * resized-variant vs original fallback in the identity projection). Storage is mocked at the
 * S3AvatarStorage seam — no S3/emulator needed.
 */
class ProfileServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String SUB = "local-abc";

    private UserAccountRepository users;
    private S3AvatarStorage storage;
    private ProfileService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        storage = mock(S3AvatarStorage.class);
        service = new ProfileService(users, storage);

        user = UserAccount.localUser(SUB, "alice@example.com", "hash", "Alice");
        ReflectionTestUtils.setField(user, "id", USER_ID); // id is JPA-generated in prod
        when(users.findBySub(SUB)).thenReturn(Optional.of(user));
    }

    @Test
    void updateDisplayName_trimsAndApplies() {
        UserAccount updated = service.updateDisplayName(SUB, "  New Name  ");

        assertThat(updated.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void updateDisplayName_unknownSub_throws() {
        when(users.findBySub("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDisplayName("ghost", "Name"))
                .isInstanceOf(InvalidAvatarException.class);
    }

    @Test
    void createAvatarUploadUrl_allowedType_generatesOwnedOriginalKey() {
        when(storage.presignUpload(anyString(), eq("image/png")))
                .thenAnswer(inv -> new PresignedUpload("https://s3/put", inv.getArgument(0), Instant.now()));

        PresignedUpload upload = service.createAvatarUploadUrl(SUB, "image/png");

        assertThat(upload.objectKey())
                .startsWith("avatars/" + USER_ID + "/")
                .endsWith("/original")
                .matches("avatars/[0-9a-f-]{36}/[0-9a-f-]{36}/original");
    }

    @Test
    void createAvatarUploadUrl_normalizesContentTypeCase() {
        when(storage.presignUpload(anyString(), eq("image/jpeg")))
                .thenAnswer(inv -> new PresignedUpload("https://s3/put", inv.getArgument(0), Instant.now()));

        PresignedUpload upload = service.createAvatarUploadUrl(SUB, "IMAGE/JPEG");

        assertThat(upload.objectKey()).isNotBlank();
    }

    @Test
    void createAvatarUploadUrl_disallowedType_throws() {
        assertThatThrownBy(() -> service.createAvatarUploadUrl(SUB, "application/pdf"))
                .isInstanceOf(InvalidAvatarException.class)
                .hasMessageContaining("content type");
    }

    @Test
    void confirmAvatar_ownKeyWithImageObject_persistsKey() {
        String key = "avatars/" + USER_ID + "/" + UUID.randomUUID() + "/original";
        when(storage.contentTypeOf(key)).thenReturn(Optional.of("image/png"));

        UserAccount updated = service.confirmAvatar(SUB, key);

        assertThat(updated.getAvatarKey()).isEqualTo(key);
    }

    @Test
    void confirmAvatar_foreignUserPrefix_throws() {
        String foreignKey = "avatars/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/original";

        assertThatThrownBy(() -> service.confirmAvatar(SUB, foreignKey))
                .isInstanceOf(InvalidAvatarException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void confirmAvatar_malformedKeyEvenUnderOwnPrefix_throws() {
        String traversal = "avatars/" + USER_ID + "/../../etc/original";

        assertThatThrownBy(() -> service.confirmAvatar(SUB, traversal))
                .isInstanceOf(InvalidAvatarException.class);
    }

    @Test
    void confirmAvatar_objectNeverUploaded_throws() {
        String key = "avatars/" + USER_ID + "/" + UUID.randomUUID() + "/original";
        when(storage.contentTypeOf(key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmAvatar(SUB, key))
                .isInstanceOf(InvalidAvatarException.class)
                .hasMessageContaining("No uploaded object");
    }

    @Test
    void confirmAvatar_storedObjectNotAnImage_throws() {
        String key = "avatars/" + USER_ID + "/" + UUID.randomUUID() + "/original";
        when(storage.contentTypeOf(key)).thenReturn(Optional.of("text/html"));

        assertThatThrownBy(() -> service.confirmAvatar(SUB, key))
                .isInstanceOf(InvalidAvatarException.class)
                .hasMessageContaining("not an allowed image type");
    }

    @Test
    void toUserInfo_noAvatar_nullUrls() {
        UserInfoResponse info = service.toUserInfo(user, false);

        assertThat(info.avatarUrl()).isNull();
        assertThat(info.avatarThumbUrl()).isNull();
        assertThat(info.name()).isEqualTo("Alice");
    }

    @Test
    void toUserInfo_resizedVariantsExist_servesWebpUrls() {
        String key = "avatars/" + USER_ID + "/" + UUID.randomUUID() + "/original";
        String base = key.substring(0, key.length() - "/original".length());
        user.changeAvatar(key);
        when(storage.exists(base + "/256.webp")).thenReturn(true);
        when(storage.presignDownload(base + "/256.webp")).thenReturn("https://s3/256");
        when(storage.presignDownload(base + "/64.webp")).thenReturn("https://s3/64");

        UserInfoResponse info = service.toUserInfo(user, true);

        assertThat(info.avatarUrl()).isEqualTo("https://s3/256");
        assertThat(info.avatarThumbUrl()).isEqualTo("https://s3/64");
        assertThat(info.isAdmin()).isTrue();
    }

    @Test
    void toUserInfo_lambdaNotDoneYet_fallsBackToOriginalForBothSlots() {
        String key = "avatars/" + USER_ID + "/" + UUID.randomUUID() + "/original";
        user.changeAvatar(key);
        when(storage.exists(anyString())).thenReturn(false);
        when(storage.presignDownload(key)).thenReturn("https://s3/original");

        UserInfoResponse info = service.toUserInfo(user, false);

        assertThat(info.avatarUrl()).isEqualTo("https://s3/original");
        assertThat(info.avatarThumbUrl()).isEqualTo("https://s3/original");
    }
}
