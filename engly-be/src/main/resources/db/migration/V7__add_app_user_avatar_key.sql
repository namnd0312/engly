-- S3 object key of the user's current avatar ORIGINAL upload
-- (shape: avatars/{user_id}/{upload_id}/original). NULL = no avatar set.
-- Resized WebP variants (256.webp, 64.webp) live at deterministic sibling keys
-- written by the avatar-resize Lambda; only the original's key is persisted.
ALTER TABLE app_user ADD COLUMN avatar_key varchar(512) NULL;
