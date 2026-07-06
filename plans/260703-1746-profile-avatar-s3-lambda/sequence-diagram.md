# Sequence Diagram — Profile Update + Avatar Upload (S3 presigned PUT + Lambda resize)

Luồng đầy đủ: (A) cập nhật display name, (B) upload avatar direct-to-S3 + Lambda resize, (C) hiển thị.

```mermaid
sequenceDiagram
    autonumber
    actor U as Browser (Angular SPA)
    participant BE as Spring Boot<br/>(ProfileController/Service)
    participant DB as Postgres<br/>(app_user)
    participant S3 as Floci S3<br/>(engly-avatars)
    participant L as Lambda<br/>(Python + Pillow)

    Note over U,DB: A) Cập nhật display name
    U->>BE: PATCH /api/profile { displayName } (cookie ACCESS_TOKEN)
    BE->>DB: findBySub(sub) → updateDisplayName()
    DB-->>BE: UserAccount (updated)
    BE-->>U: 200 UserInfoResponse (name mới)
    U->>U: auth.setUser(resp) → header cập nhật

    Note over U,L: B) Upload avatar (direct-to-S3, any size)
    U->>BE: POST /api/profile/avatar/upload-url { contentType }
    BE->>BE: allowlist image/* + gen key<br/>avatars/{userId}/{uploadId}/original
    BE->>S3: S3Presigner.presignPutObject (public-endpoint)
    S3-->>BE: presigned PUT URL
    BE-->>U: { uploadUrl, objectKey, expiresAt }

    U->>S3: PUT file → uploadUrl (KHÔNG qua backend,<br/>no cookie, Content-Type khớp chữ ký)
    S3-->>U: 200 (ETag)

    S3-)L: s3:ObjectCreated (prefix avatars/, suffix original)
    activate L
    L->>S3: GetObject(original)
    L->>L: Pillow: exif_transpose + fit(256/64) → WebP
    L->>S3: PutObject 256.webp + 64.webp (siblings)
    deactivate L

    U->>BE: POST /api/profile/avatar/confirm { objectKey }
    BE->>BE: validate ownership prefix + shape regex
    BE->>S3: HeadObject(objectKey) → content-type still image/*
    S3-->>BE: metadata OK
    BE->>DB: changeAvatar(objectKey)
    BE->>S3: HeadObject(256.webp)?
    alt Lambda đã xong (256.webp tồn tại)
        S3-->>BE: exists → presign 256/64
    else Chưa xong (fallback)
        S3-->>BE: NoSuchKey → presign original cho cả 2
    end
    BE-->>U: 200 { avatarUrl, avatarThumbUrl } (presigned GET)

    Note over U,S3: C) Hiển thị avatar / lần /api/me sau tự nâng cấp
    U->>BE: GET /api/me
    BE->>DB: findBySub(sub)
    BE->>S3: HeadObject(256.webp) → presign 256/64
    BE-->>U: UserInfoResponse (URL webp đã resize)
    U->>S3: <img src=presigned GET> → tải ảnh WebP
    S3-->>U: 256x256 / 64x64 WebP
```

## Điểm mấu chốt

- **Bước 8 (PUT file):** browser → S3 trực tiếp, không qua Spring → đúng nghĩa *any size*, không đụng
  giới hạn multipart/nginx/pod memory.
- **Endpoint split:** presign dùng `S3_PUBLIC_ENDPOINT` (`localhost:4566`, browser hit được);
  HeadObject của backend dùng `S3_ENDPOINT` (`host.orb.internal:4566` trong pod). Chữ ký presigned
  gắn với host nên phải tách — giống precedent OIDC `authorization-uri` vs `token-uri`.
- **`alt` ở confirm & /api/me:** graceful degradation — Lambda chưa resize xong thì serve ảnh gốc,
  lần `/api/me` sau tự nâng cấp lên `256.webp` (1 HeadObject, không polling, Lambda không ghi DB).
- **Chống recursion:** derived key (`256.webp`, `64.webp`) không kết thúc bằng `original` → S3
  notification (suffix `original`) không tự trigger lại Lambda.

## Security checkpoints (trên diagram)

| Bước | Kiểm tra |
|------|----------|
| upload-url | content-type allowlist; key do backend sinh (client không chọn) |
| PUT S3 | Content-Type ký sẵn trong URL → S3 từ chối nếu sai |
| confirm | ownership prefix `avatars/{userId}/` + shape regex + HeadObject existence + content-type còn là image/* |
| tất cả | `/api/profile/**` yêu cầu authenticated (cookie JWT) |
