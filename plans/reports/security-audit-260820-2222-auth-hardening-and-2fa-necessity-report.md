# Security Audit — Auth Posture & 2FA Necessity

**Date:** 2026-08-20 · **Branch:** main · **Scope:** `engly-be` identity module, `SecurityConfig`, k8s auth env
**Trigger question:** "Có cần apply 2FA cho hệ thống này không?"

---

## TL;DR

**2FA toàn hệ thống: KHÔNG cần.** App không giữ tiền, PII nhạy cảm, hay nội dung riêng tư. Chiếm account user thường chỉ đọc được tiến độ học flashcard.

**Nhưng lớp phòng thủ thứ nhất đang mở.** `/api/auth/login` không có bất kỳ giới hạn nào về số lần thử. Đây là rủi ro thật, cần xử lý trước bất kỳ bàn luận nào về 2FA.

**Chỗ 2FA có ROI thật: `ROLE_ADMIN`** — ít người, khôi phục dễ, mà quyền thì đủ để xoá/ghi đè toàn bộ corpus 9000+ từ.

**Thứ tự đề xuất:** rate limiting → audit log → password policy → forgot-password → (rồi mới) TOTP cho admin.

---

## 1. Tài sản đang được bảo vệ

| Tài sản | Nơi lưu | Mức |
|---|---|---|
| Tiến độ học (`user_card_state`, `review_log`, `user_study_settings`) | Postgres, key theo `user_sub` | Thấp |
| Email, display name, avatar key | `app_user` | Thấp |
| Vocab/verb corpus + CSV bulk import | `/api/admin/**`, tus upload | **Trung bình–cao** |
| Thanh toán / PII quy định / nội dung riêng tư | — không có — | N/A |

Kết luận threat model: kẻ tấn công **không có động cơ tài chính** để chiếm account user thường. Động cơ duy nhất đáng kể là **phá hoại nội dung qua account admin**.

---

## 2. Những gì auth hiện tại đã làm ĐÚNG

Xác minh bằng đọc code, không phải suy đoán. Liệt kê ra để không ai "sửa" nhầm:

- **Token storage** — refresh token + email verification token đều 256-bit `SecureRandom`, lưu SHA-256 hash, raw chỉ tồn tại trong cookie/email link. (`AuthTokenService.createRefreshToken`, `EmailVerificationService.startVerification`)
- **Không rò rỉ account enumeration** — login trả 401 chung cho cả email không tồn tại lẫn sai mật khẩu; `EMAIL_NOT_VERIFIED` chỉ lộ ra SAU khi password đã đúng. `resend-verification` luôn 202. (`UserAccountService.login`)
- **Social account linking an toàn** — chỉ link khi `email_verified` đúng ở **cả hai phía**; password account không bao giờ được auto-verify nên không bị merge ngầm. (V10 migration + `SocialLoginService`)
- **Login-CSRF guard trên `/verify-email`** — trả 409 nếu đã có session, chặn việc thay session ngầm. (`AuthController.verifyEmail`)
- **CSRF bật** với `CookieCsrfTokenRepository`; mỗi exemption đều có lý do viết rõ. Path tus tuy exempt nhưng đã admin-gated + bắt buộc custom header `Tus-Resumable` (CORS preflight chặn cross-site).
- **Không có đường leo thang quyền** — `ROLE_ADMIN` chỉ cấp bằng INSERT tay vào `app_user_role`, không có endpoint tự cấp.
- **Cookie httpOnly + SameSite=Lax**, refresh cookie scope hẹp ở `/api/auth`.
- **`ddl-auto: validate`** + Flyway — schema không tự đổi lúc runtime.

Nền tảng tốt. Các phát hiện dưới đây là lớp thiếu chứ không phải lỗi thiết kế.

---

## 3. Phát hiện

### HIGH

#### H1 — Không có brute-force protection trên login
**File:** `engly-be/src/main/java/com/engly/common/SecurityConfig.java`, `identity/api/AuthController.java:69`

Không có rate limit, không lockout, không đếm failed attempt, không throttle theo IP. Xác minh: grep `RateLimit|bucket4j|attempt|failedAttempt` toàn `engly-be/src/main/java` → **0 kết quả**; `pom.xml` không có dependency rate-limit nào; `SecurityConfig` không có filter throttling.

Phanh duy nhất hiện tại là chi phí BCrypt (strength mặc định 10, ~50–100ms/lần). Không đủ: kẻ tấn công chạy song song nhiều connection vẫn đạt throughput cao, và không có gì ghi nhận hay chặn khi bị tấn công.

Kết hợp với **M3 (password tối thiểu 8 ký tự, không kiểm tra độ phức tạp/breach list)** → credential stuffing bằng danh sách password rò rỉ là khả thi.

Ảnh hưởng cả `/api/auth/refresh` và `/api/auth/verify-email`, nhưng token 256-bit làm việc đoán token bất khả thi — nên login mới là bề mặt thật.

> **Đây là lý do 2FA cho user thường là sai thứ tự ưu tiên.** 2FA là lớp 2. Lớp 1 đang không có phanh.

### MEDIUM

#### M2 — Refresh token rotation không có reuse detection
**File:** `identity/application/AuthTokenService.java:73` (`rotate`)

`rotate()` revoke token cũ + phát token mới. Nhưng khi một token **đã bị revoke** được trình lại, hệ thống chỉ trả 401 — không revoke cả "family", không cảnh báo.

Hệ quả thực tế: nếu refresh token bị đánh cắp và attacker rotate **trước**, attacker giữ được chuỗi token hợp lệ, còn nạn nhân bị 401 ở lần refresh kế tiếp và chỉ thấy như một lần "tự đăng xuất" ngẫu nhiên. Không ai biết có vụ trộm.

Nghiêm trọng hơn vì **refresh TTL = 30 ngày** (`JWT_REFRESH_TOKEN_TTL:30d`) — cửa sổ rất dài cho một token không có cơ chế phát hiện đánh cắp.

Cách xử lý chuẩn: khi nhận token đã revoke nhưng có trong DB → revoke toàn bộ token của user đó + ghi log.

#### M3 — Password policy yếu
**File:** `identity/api/dto/RegisterRequest.java:11`

Chỉ `@Size(min = 8, max = 100)`. Không yêu cầu độ phức tạp, không đối chiếu breach list (HIBP k-anonymity API là lựa chọn rẻ). Tự nó chấp nhận được với NIST 800-63B, **nhưng NIST cho phép bỏ quy tắc phức tạp với điều kiện bắt buộc có breach-check + rate limiting** — hiện đang thiếu cả hai.

#### M4 — Không có audit trail cho auth
Không ghi nhận login thành công/thất bại, IP, user-agent, hay hành động admin. Ba hệ quả:
1. Không phát hiện được tấn công đang diễn ra.
2. Không điều tra được sau sự cố.
3. **Chặn luôn việc làm lockout/rate limiting** — không có chỗ nào để đếm.

Nên làm cùng lúc với H1, không phải sau.

#### M5 — Chưa có forgot-password flow
Không có DTO/endpoint reset password. User quên mật khẩu = mất account vĩnh viễn (trừ khi có SSO đã link).

Rủi ro không phải ở hiện tại mà ở tương lai: khi bị áp lực phải thêm gấp, reset-token flow là bề mặt account-takeover kinh điển. **Thiết kế nó cùng với rate limiting**, dùng chung hạ tầng token đã có (`TokenHashing` + pattern của `email_verification_token`).

### LOW

| ID | Phát hiện | Ghi chú |
|---|---|---|
| L1 | **Login CSRF** — `/api/auth/login` vừa CSRF-exempt vừa tạo session, không có guard "đã đăng nhập" như `/verify-email` có. Attacker ép browser nạn nhân đăng nhập vào account của attacker → dữ liệu học của nạn nhân chảy vào đó. Tác động thấp (không có tiền/PII), fix giống hệt pattern ở `verifyEmail`. |
| L2 | **Access JWT không revoke được khi logout** — `clearSession` revoke refresh + xoá cookie, nhưng access JWT đã bị copy vẫn sống tới hết TTL. Bản chất của stateless JWT; TTL 15m là giới hạn hợp lý. **Non-issue** ở quy mô hiện tại. |
| L3 | **Không dọn token hết hạn** — `refresh_token` (30d TTL) và `email_verification_token` không có job purge. Bảng phình vô hạn. Housekeeping. |
| L4 | **`AUTH_COOKIE_SECURE=false`** trong `k8s/20-engly-be-backend.yaml:50`. Đúng cho OrbStack NodePort HTTP, nhưng là blocker cứng cho mọi deploy thật. Đã có comment cảnh báo trong manifest. |
| L5 | **Cooldown resend-verification là per-user (60s), không per-IP** — `EmailVerificationService.resend`. Bắn phá một user đã bị chặn, nhưng lặp qua nhiều email khác nhau thì không giới hạn → đốt quota SMTP. Nhẹ hơn dự đoán ban đầu nhờ có cooldown per-user. |
| L6 | **`JWT_SECRET` là `optional: true`** trong k8s (`20-engly-be-backend.yaml:46`). Thiếu secret → app vẫn boot với key ephemeral, log WARN, và **im lặng đăng xuất toàn bộ user mỗi lần restart pod**. Cân nhắc fail-fast khi chạy profile prod. |

---

## 4. Trả lời trực tiếp: có cần 2FA không?

### User thường — KHÔNG

- Không có tài sản để kiếm lời từ việc chiếm account.
- Bắt buộc 2FA sẽ giết conversion đăng ký ở giai đoạn còn đang xây user base.
- Kéo theo đuôi dài vận hành: backup codes, flow mất thiết bị, support ticket. Với project solo, chi phí này lớn hơn rủi ro nó chặn.
- **Opt-in** thì vô hại nhưng gần như không ai bật — công sức không đổi mà giá trị thực tế ~0.

### `ROLE_ADMIN` — CÓ, nhưng không phải việc cần làm ngay

Quyền admin mở `/api/admin/**`: CRUD deck, CRUD vocab word, bulk CSV import qua tus (giới hạn 2GB). Một account admin bị chiếm = corpus bị ghi đè hoặc xoá. Số admin ít nên flow khôi phục không thành gánh nặng support.

**Nếu làm:** TOTP (RFC 6238) + backup codes. Không SMS (tốn phí, rủi ro SIM swap), không WebAuthn (overkill ở giai đoạn này).

**Lựa chọn thay thế rẻ hơn, đạt phần lớn lợi ích:** yêu cầu re-auth mật khẩu cho các thao tác phá huỷ (xoá deck, bulk import) + ghi audit log admin action. Chặn đúng kịch bản nguy hiểm nhất (session bị chiếm tạm thời) mà không cần cả hạ tầng TOTP.

---

## 5. Thứ tự ưu tiên đề xuất

| # | Việc | Xử lý | Công sức |
|---|---|---|---|
| 1 | Rate limiting + audit log cho auth | H1, M4 | S–M |
| 2 | Refresh token reuse detection (revoke family) | M2 | S |
| 3 | Breach-list check khi đăng ký/đổi mật khẩu | M3 | S |
| 4 | Forgot-password flow (thiết kế cùng #1) | M5 | M |
| 5 | Login-CSRF guard + purge token hết hạn | L1, L3 | S |
| 6 | TOTP 2FA cho admin (hoặc re-auth cho thao tác phá huỷ) | — | M |

Mục 1–3 chi phí thấp hơn 2FA và chặn được rủi ro thực tế hơn. Trước khi deploy thật: bật `AUTH_COOKIE_SECURE=true` sau TLS (L4) và đặt `JWT_SECRET` cố định (L6).

---

## Unresolved questions

1. **Có kế hoạch deploy public không?** Toàn bộ mức độ ưu tiên phụ thuộc câu này. Chạy local/OrbStack thì H1 gần như lý thuyết; mở internet thì H1 phải làm trước tiên.
2. **Hiện có bao nhiêu account `ROLE_ADMIN`, và có phải đều là bạn không?** Nếu chỉ một mình bạn, re-auth cho thao tác phá huỷ là đủ, khỏi cần TOTP.
3. **Rate limiting đặt ở tầng nào?** In-app (bucket4j) so với reverse proxy/ingress (nginx `limit_req`) — nginx đang ở trước SPA nên có thể đã đủ chỗ đặt, cần xác nhận topology prod.
4. **Có ràng buộc tuân thủ nào không** (GDPR nếu có user EU)? Nếu có thì audit log chuyển từ "nên có" sang "bắt buộc".
5. **Backup/restore cho vocab corpus có sẵn chưa?** Nếu đã restore được dễ dàng, thiệt hại từ việc admin bị chiếm giảm mạnh → 2FA admin tụt ưu tiên thêm nữa.
