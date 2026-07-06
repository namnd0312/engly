/** Presigned S3 PUT issued by POST /api/profile/avatar/upload-url. */
export interface AvatarUploadUrl {
  /** Absolute presigned URL the browser PUTs the raw file to (direct-to-S3, any size). */
  uploadUrl: string;
  /** Backend-generated object key; echo back to /avatar/confirm after the PUT succeeds. */
  objectKey: string;
  /** ISO instant after which the presigned PUT can no longer START. */
  expiresAt: string;
}
