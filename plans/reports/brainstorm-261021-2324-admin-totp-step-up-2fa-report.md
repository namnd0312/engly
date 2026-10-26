# Brainstorm — TOTP 2FA (step-up) cho ROLE_ADMIN

**Date:** 2026-10-21 · **Branch:** main · **Mode:** markdown only (no --html/--wiki)
**Input:** "tôi muốn tích hợp xác thực 2 lớp cho prj này"
**Prior evidence:** `plans/reports/security-audit-260820-2222-auth-hardening-and-2fa-necessity-report.md`

---

## 1. Problem-first inversion

**Solution-jumping diagnosis.** User brought "2FA" as a preselected solution. Audit 08/2026 already concluded: user thường không có tài sản đáng tấn công; `ROLE_ADMIN` có (CRUD + bulk import corpus 9000+ từ). Signal thật = bảo vệ corpus khỏi admin account bị chiếm.

**Underlying problem.** Một password duy nhất (hoặc một SSO session) là toàn bộ rào chắn giữa attacker và quyền xoá/ghi đè corpus. Không có lớp thứ hai, không có audit trail.

**Assumption challenges.**
| Assumption | Risk nếu sai | Validation |
|---|---|---|
| Admin là chính owner, số lượng ≤ vài người | Flow khôi phục bằng SQL không scale | Đếm `app_user_role WHERE role='ROLE_ADMIN'` |
| App chạy local/OrbStack, chưa public | Nếu public → `/login` không rate limit là lỗ hổng ưu tiên cao hơn | User xác nhận: local/portfolio |
| Step-up đủ "bắt buộc" | Nếu cần 2FA tại login đúng nghĩa thì phải làm lại gate | Threat model chỉ bảo vệ `/api/admin/**` → step-up đủ |

**Problem statement.** Admin (owner) · struggle: corpus có thể bị phá nếu password/SSO session bị lộ · cause: một yếu tố xác thực · consequence: mất dữ liệu, không có backup cam kết · success: không thể gọi `/api/admin/**` nếu không có mã TOTP hợp lệ, user thường không bị ảnh hưởng.

**Three framings.**
- A. "Bảo vệ corpus" → 2FA admin **hoặc** re-auth trước thao tác phá huỷ **hoặc** backup/restore corpus tự động. → chọn 2FA admin (user quyết định).
- B. "Bảo vệ user thường" → rate limit + breach-check password. Bác bỏ: không có tài sản.
- C. "Portfolio / học" → làm chuẩn RFC 6238 tự viết + test vector. User chọn tự viết → frame C cũng được thoả.

**Evidence status:** Medium (audit code-based + threat model rõ, chưa có incident thực).

---

## 2. Requirements đã chốt

| Item | Quyết định |
|---|---|
| Đối tượng | Chỉ `ROLE_ADMIN`, bắt buộc để vào `/api/admin/**`. User thường: UI enroll vẫn hiện (0 chi phí thêm) nhưng không ép |
| Yếu tố 2 | TOTP RFC 6238, 6 số, step 30s, SHA-1, window ±1 |
| Gate model | **Step-up**: login phát session `mfa=false`; `/api/admin/**` cần `mfa=true` |
| SSO | Phủ tự động (step-up sau khi có session), `SsoAuthenticationSuccessHandler` không đổi |
| Khôi phục | Không backup codes. Reset bằng SQL `DELETE FROM user_mfa_totp WHERE user_id=...`. Ghi vào deployment-guide |
| Secret at rest | AES-256-GCM, key SHA-256 từ env `MFA_SECRET_KEY` (pattern `JwtKeyConfig`), ephemeral + WARN khi thiếu |
| TOTP lib | Tự viết (~80 LOC) + Base32; **bắt buộc** test với vector RFC 6238 Appendix B |
| Deploy | Local/OrbStack, portfolio → rate limit `/login` hoãn |

**Out of scope:** backup codes, trusted device, rate limit `/login`, re-auth thao tác phá huỷ, 2FA bắt buộc cho user thường, audit log.

---

## 3. Approaches evaluated

### Gate model
| | A. Step-up (chosen) | B. Chặn tại login |
|---|---|---|
| Flow | 1 flow cho password + SSO | Pending cookie 5' + trạng thái auth thứ 3 |
| SSO | Không sửa | Sửa success handler + redirect `/login/mfa` |
| Admin dùng app như học viên | Có, không cần mã | Không |
| Bảo vệ corpus | Tương đương | Tương đương |
| Test effort | 1x | ~1.5x |

### Second factor
TOTP (chosen) vs Email OTP: email OTP phụ thuộc SMTP (local fail silently), cùng kênh với reset password → yếu hơn. TOTP offline, chuẩn.

### Secret storage
AES-GCM env key (chosen) vs plaintext: ~30 LOC đổi lấy "DB lộ ≠ 2FA lộ". Trade-off: mất key = enroll lại.

### Library
Tự viết (chosen) vs `dev.samstevens.totp`: user chọn tự viết vì portfolio. Rủi ro time-step/constant-time → bắt buộc test vector chuẩn + `MessageDigest.isEqual`.

---

## 4. Final design

### Data (Flyway V12)
```sql
CREATE TABLE user_mfa_totp (
  user_id         uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  secret_enc      varchar(255) NOT NULL,   -- base64(iv || AES-GCM ciphertext)
  confirmed_at    timestamptz NULL,        -- NULL = enrollment pending
  last_used_step  bigint NULL,             -- replay guard
  failed_attempts int  NOT NULL DEFAULT 0,
  locked_until    timestamptz NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE refresh_token ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;
```

### Backend (`com.engly.identity`)
| File | Loại | Vai trò |
|---|---|---|
| `application/TotpCodec.java` | new | Base32 + HMAC-SHA1 RFC 6238, `generate(secret, step)`, `verify(secret, code, nowStep, window=1)` → trả step khớp hoặc empty; constant-time |
| `application/MfaSecretCipher.java` | new | AES-256-GCM encrypt/decrypt, key từ `MfaProperties` |
| `config/MfaProperties.java` | new | `app.security.mfa.secret-key` ← `MFA_SECRET_KEY`, issuer label cho otpauth |
| `domain/UserMfaTotp.java` | new | Entity + `isLocked(now)`, `recordFailure()`, `recordSuccess(step)` |
| `infrastructure/UserMfaTotpRepository.java` | new | JPA |
| `application/MfaService.java` | new | `beginEnrollment(user)`, `confirmEnrollment(user, code)`, `verify(user, code)`, `disable(user, code)`, `isEnrolled(userId)` |
| `api/MfaController.java` | new | `POST /api/profile/mfa/enroll`, `POST /api/profile/mfa/confirm`, `DELETE /api/profile/mfa`, `POST /api/auth/mfa/verify` |
| `api/dto/MfaEnrollResponse`, `MfaCodeRequest` | new | DTO; code `@Pattern("\\d{6}")` |
| `domain/MfaRequiredException`, `MfaEnrollmentRequiredException`, `InvalidMfaCodeException`, `MfaLockedException` | new | → `GlobalExceptionHandler` map 403 `MFA_REQUIRED` / 403 `MFA_ENROLLMENT_REQUIRED` / 400 `INVALID_MFA_CODE` / 429 `MFA_LOCKED` |
| `application/AuthTokenService.java` | modify | claim `mfa` trong access JWT; `issueSession` → false; `rotate` copy `mfa_verified`; new `upgradeToMfa(request, response)` re-mint access + set flag trên refresh token hiện tại |
| `domain/RefreshToken.java` | modify | field `mfaVerified` + `markMfaVerified()` |
| `common/SecurityConfig.java` | modify | `/api/admin/**` → `.access(adminWithMfa)` — AuthorizationManager: `ROLE_ADMIN` AND `jwt.mfa == true`; deny reason phân biệt enrolled/not → qua `AccessDeniedHandler` trả JSON code |
| `api/SessionController.java` + `dto/UserInfoResponse.java` | modify | thêm `mfaEnrolled`, `mfaVerified` (từ DB + claim) |
| `application.yml`, `k8s/20-engly-be-backend.yaml`, `docs/deployment-guide.md` | modify | `MFA_SECRET_KEY`, lệnh SQL reset |

Hành vi `verify(user, code)`: locked → 429; sai → `failed_attempts++`, ≥5 → `locked_until = now+15m`; đúng nhưng `step <= last_used_step` → coi như sai (replay); đúng → reset counter, `last_used_step = step`.
Enrollment chưa confirm: `beginEnrollment` ghi đè row pending cũ; `confirmEnrollment` yêu cầu `confirmed_at IS NULL`. `disable` yêu cầu mã hợp lệ. Sau `disable`, mọi refresh token của user → `mfa_verified=false`.

### Frontend (`engly-fe`)
| File | Loại | Vai trò |
|---|---|---|
| `core/auth.service.ts` | modify | `UserInfo` + `mfaEnrolled`, `mfaVerified`; `verifyMfa(code)` |
| `features/profile/security/mfa-settings.component.ts` | new | 3 state: off → enrolling (QR via `angularx-qrcode` + input) → on (disable) |
| `features/profile/data/mfa-api.service.ts` | new | enroll/confirm/disable |
| `features/auth/mfa-verify-page.component.ts` | new | route `/mfa/verify?returnUrl=`, 6 số, lỗi 400/429 |
| `features/admin/admin.guard.ts` | modify | `isAdmin && !mfaEnrolled` → `/profile?mfa=required`; `isAdmin && !mfaVerified` → `/mfa/verify` |
| `core/auth-refresh.interceptor.ts` (hoặc interceptor mới) | modify | 403 `MFA_REQUIRED` → redirect `/mfa/verify` |
| `app.routes.ts`, `profile-page.component.html` | modify | route + nhúng section |
| `package.json` | modify | `angularx-qrcode` |

### Sequence (admin, lần đầu)
1. Login (password hoặc SSO) → cookies, JWT `mfa=false`, `/api/me` → `mfaEnrolled=false`.
2. Vào `/admin` → guard → `/profile?mfa=required` → Bật 2FA → QR → nhập mã → confirm.
3. Vào `/admin` → guard → `/mfa/verify` → nhập mã → `POST /api/auth/mfa/verify` → access cookie mới `mfa=true`, refresh token `mfa_verified=true`.
4. 15' sau access hết hạn → `rotate()` giữ `mfa=true` → không hỏi lại trong 30 ngày refresh.

---

## 5. Risks & mitigations
| Rủi ro | Mitigation |
|---|---|
| Tự viết TOTP sai | Unit test với RFC 6238 Appendix B vectors (SHA-1, T=59/1111111109/…); `MessageDigest.isEqual` |
| Lệch giờ server | Window ±1; ghi chú trong deployment-guide |
| Mất `MFA_SECRET_KEY` | Fail-fast WARN rõ; đặt cố định trong k8s secret cùng `JWT_SECRET` |
| Mất điện thoại | SQL reset documented; admin = owner nên chấp nhận |
| Brute-force `/mfa/verify` | Lock 5/15' per user (DB), không cần bucket4j |
| Admin bị khoá 2FA giữa chừng (disable rồi vào admin) | `disable` reset `mfa_verified` toàn bộ refresh token; guard dựa vào `/api/me` mới |

## 6. Success criteria
- `GET /api/admin/decks` với JWT `ROLE_ADMIN, mfa=false` → 403 `MFA_REQUIRED` (đã enroll) / `MFA_ENROLLMENT_REQUIRED` (chưa).
- Sau `POST /api/auth/mfa/verify` đúng mã → 200 cho cùng request. Sai 5 lần → 429 trong 15'.
- Mã đã dùng không dùng lại được trong cùng step.
- `rotate()` giữ `mfa=true`; logout/disable xoá.
- Login Google với admin đã enroll → vẫn bị chặn `/admin` tới khi verify.
- User thường: toàn bộ test hiện có pass không đổi; `/api/me` có thêm 2 field.
- `TotpCodecTest` pass vector RFC 6238; backend 24 test cũ + FE 13 spec cũ không regress.

## 7. Next steps
`/ck:plan` với report này làm input. Khuyến nghị `--tdd`: sửa `AuthTokenService.rotate`, `SecurityConfig`, `adminGuard` là business logic critical đã có coverage (`AuthFlowIntegrationTest`, `auth.service.spec`).

## Unresolved questions
1. Hiện có bao nhiêu row `ROLE_ADMIN` trong DB local/k8s? (ảnh hưởng ghi chú migration, không ảnh hưởng thiết kế)
2. Issuer label trong otpauth URI: "Engly" hay theo `app.frontend-url`? Mặc định "Engly".
3. Có muốn admin **không được** tự disable 2FA (chỉ reset bằng SQL)? Thiết kế hiện cho phép disable với mã hợp lệ.
