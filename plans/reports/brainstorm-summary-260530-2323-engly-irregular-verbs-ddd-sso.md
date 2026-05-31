# Brainstorm Summary — Engly: Tra cứu động từ bất quy tắc (MVP) + nền tảng DDD-ready + SSO

- **Date:** 2026-05-30
- **Stack chốt:** Backend Java Spring Boot · Frontend Angular · PostgreSQL · SSO qua OPEN-GATE-APP (OAuth2/OIDC)
- **Loại:** Greenfield, monorepo

---

## 1. Problem statement & Requirements

Xây web app học tiếng Anh, kiến trúc sẵn sàng mở rộng nhiều feature tương lai.
**MVP duy nhất:** tra cứu động từ bất quy tắc (public). Tái sử dụng SSO từ `OPEN-GATE-APP` (đã là OAuth2/OIDC Authorization Server chuẩn).

**Functional (MVP):**
- Tra cứu động từ: search theo base form / nghĩa, trả về `base / past / past participle` + IPA + nghĩa tiếng Việt.
- Public, không cần đăng nhập.
- **Spike SSO:** 1 luồng login tối thiểu (đăng ký client + `/me`) để chứng minh tích hợp OPEN-GATE-APP chạy được.

**Non-functional:**
- Kiến trúc tiến hóa được thành nhiều bounded context (DDD tactical áp dần).
- Bảo mật: BFF pattern cho SSO (token giữ server-side).
- File code < 200 dòng, package-by-feature, kebab-case.

---

## 2. Quyết định đã chốt (qua interview)

| # | Quyết định | Lựa chọn | Lý do |
|---|-----------|----------|-------|
| 1 | Mức DDD | **Modular monolith + tactical DDD dần** | Tránh over-engineer feature tra cứu; vẫn sẵn sàng mở rộng |
| 2 | SSO pattern | **BFF (Spring Boot giữ token)** | Không có token trong JS → chống XSS; best-practice IETF cho SPA |
| 3 | Auth cho verb lookup | **Public, không login** | Dữ liệu tham khảo công khai → ship MVP nhanh |
| 4 | Quyền OPEN-GATE-APP | **Có admin + chạy local song song** | Tích hợp SSO khả thi ngay |
| 5 | Trình tự | **MVP public + spike SSO nhỏ** | Validate luồng SSO sớm nhưng không chặn feature |
| 6 | Repo | **Monorepo** (`backend/`, `frontend/`) | Dễ quản version chung, 1 CI |
| 7 | Trường dữ liệu verb | **base/past/pp + nghĩa VI + IPA** | (bỏ câu ví dụ ở MVP) |

---

## 3. Hiện trạng 2 dự án (Scout findings)

**OPEN-GATE-APP** — IdP standalone hoàn chỉnh, **không cần copy code**, chỉ tích hợp:
- Spring Boot 4.0.6, Java 21, Spring Authorization Server.
- RS256 JWT, JWKS tại `/oauth2/jwks`, Authorization Code + PKCE.
- Đăng ký client qua `POST /admin/clients` (cần ROLE_ADMIN).
- **Không có `/userinfo`** → claim lấy từ ID token (OIDC).
- Endpoints: `/oauth2/authorize`, `/oauth2/token`, `/oauth2/revoke`, `/.well-known/openid-configuration`.

**Engly** — greenfield. ⚠️ Có plan cũ `plans/260509-2058-...` giả định **Next.js + JWT tự phát hành + scope lớn** → **stale, mâu thuẫn hướng mới**, bỏ qua.

---

## 4. Giải pháp khuyến nghị (Final)

### 4.1 Cấu trúc monorepo
```
engly/
├── backend/        # Spring Boot, Java 21
├── frontend/       # Angular standalone
├── docker-compose.yml   # Postgres (Engly) — OPEN-GATE-APP chạy riêng
├── docs/
└── plans/
```

### 4.2 Backend — modular monolith, package-by-feature
```
com.engly
├── verb/                 # Bounded context MVP (giữ PHẲNG, không aggregate ceremony)
│   ├── api/              # VerbController (REST, public)
│   ├── application/      # VerbQueryService
│   ├── domain/           # IrregularVerb (entity), VerbRepository (port)
│   └── infrastructure/   # JPA repo impl
├── identity/             # SSO/BFF (spike MVP → mở rộng sau)
│   ├── api/              # SessionController: /api/me, logout
│   └── config/           # OAuth2 client config (oauth2Login OIDC)
├── common/               # SecurityConfig, CORS, CSRF, error handling
└── EnglyApplication.java
```
→ Verb module cố tình tối giản (controller→service→repo). Tactical DDD (aggregate/value object/domain event) **chỉ áp khi module phức tạp** (SRS, gamification tương lai). Layout đã sẵn để tách microservice sau.

### 4.3 SSO / BFF (spike)
- Engly BE = **confidential OAuth2 client** đăng ký trong OPEN-GATE-APP.
  - grant: `authorization_code` + `refresh_token`; scopes `openid profile email`.
  - redirect URI: `http://localhost:8081/login/oauth2/code/opengate`.
- BE dùng `spring-boot-starter-oauth2-client` + `oauth2Login()` (OIDC) → Spring tự populate `OidcUser` từ id_token (giải quyết việc thiếu `/userinfo`).
- **Session cookie httpOnly** giữa Angular ↔ Engly BE; token (access/refresh) nằm trong authorized-client store server-side, **không lộ ra browser**.
- Angular: nút "Login" → gọi BE `/oauth2/authorization/opengate` → redirect sang OPEN-GATE-APP (PKCE) → callback → BE set cookie → quay lại Angular.
- `GET /api/me` (BFF) → trả identity từ session (sub, email, name). Đây là proof tích hợp.

### 4.4 Verb API + dữ liệu
- `GET /api/verbs?q=&page=&size=` (search + paginate, public).
- `GET /api/verbs/{baseForm}` (detail).
- Bảng `irregular_verb`: `id, base_form, past_simple, past_participle, ipa_base, ipa_past, ipa_pp, meaning_vi`, (cột alternate cho dạng kép như learnt/learned).
- Seed ~180 động từ bất quy tắc phổ biến qua **Flyway migration**.

### 4.5 Frontend Angular
- Standalone components, lazy-load feature `verb-search`.
- Trang search: ô tìm (debounce), kết quả card/table (base/past/pp + IPA + nghĩa VI), trang detail.
- `AuthService` đọc `/api/me`; nút login/logout — **optional, không chặn tra cứu**.
- HTTP `withCredentials: true` cho session cookie.
- **Khuyến nghị mạnh:** dùng **Angular dev proxy** (`proxy.conf.json`) trỏ `/api` → BE:8081 để FE+BE **same-origin** trong dev → né phần lớn rắc rối CORS/CSRF/SameSite cookie.

### 4.6 Dev environment (3 process)
| Service | Port (gợi ý) |
|---------|------|
| OPEN-GATE-APP (IdP) | 9000 |
| Engly BE | 8081 |
| Angular dev | 4200 (proxy /api → 8081) |
| Postgres (Engly) | 5433 (tránh đụng IdP 5432) |

---

## 5. Approaches đã cân nhắc

| Vấn đề | Chọn | Bỏ | Lý do bỏ |
|--------|------|-----|----------|
| Kiến trúc | Modular monolith + tactical DDD | Full strategic DDD | Over-engineer cho 1 feature tra cứu (vi phạm KISS/YAGNI) |
| | | Layered thuần | Khó tiến hóa thành nhiều context |
| SSO | BFF | SPA-direct PKCE | Token trong browser → rủi ro XSS đánh cắp |
| Auth model | Tự phát hành JWT (plan cũ) | — | Đã có IdP chuẩn (OPEN-GATE-APP) → DRY, không tự dựng lại auth |

---

## 6. Risks & Mitigation

1. **Sourcing dữ liệu IPA + nghĩa VI (~180 verbs)** — *Medium*. Đây là công sức nội dung chính, cần verify độ chính xác. → Bắt đầu base/past/pp (kiến thức công khai), bồi IPA/nghĩa từ dataset rồi rà soát; có thể dùng `ai-multimodal`/dataset hỗ trợ, đánh dấu cột `verified`.
2. **CORS/CSRF/cookie cross-origin (4200↔8081)** — *Medium*. → Dùng Angular dev proxy để same-origin; CSRF dùng `CookieCsrfTokenRepository`, Angular gửi `X-XSRF-TOKEN`.
3. **Spring Boot 4.0.6 (bleeding edge) bên IdP** — *Low*. OAuth2 client/OIDC là chuẩn interop → Engly có thể dùng Boot 3.4.x ổn định HOẶC match 4.0.x; luồng OIDC vẫn tương thích. → Quyết định version ở phase setup.
4. **Thiếu `/userinfo` ở IdP** — *Resolved*. Dùng `oauth2Login` OIDC → claim từ id_token (OidcUser).
5. **Scope creep DDD** — *Low-Medium*. → Ép quy tắc verb module phẳng, file < 200 dòng.

---

## 7. Success metrics

- **Verb lookup:** search "go" → trả `go / went / gone` + IPA + nghĩa VI, public, p95 < 200ms.
- **SSO spike:** bấm Login → hoàn tất full round-trip OAuth2 với OPEN-GATE-APP → `/api/me` trả đúng identity; token không xuất hiện trong browser storage/JS.
- Kiến trúc: thêm 1 bounded context mới (vd vocabulary) không phải sửa verb module.

---

## 8. Next steps & Dependencies

1. Đăng ký Engly làm confidential client trên OPEN-GATE-APP (`POST /admin/clients`).
2. Setup monorepo + Spring Boot skeleton (verb + identity + common) + Angular skeleton.
3. Flyway: schema `irregular_verb` + seed dữ liệu.
4. Verb API + Angular search UI (public path).
5. BFF spike: oauth2Login + `/api/me` + nút login Angular.
6. docker-compose Postgres + dev proxy config.

**Dependencies:** OPEN-GATE-APP chạy được local; quyết định version Spring Boot (3.4.x vs 4.0.x) ở phase setup; nguồn dữ liệu verb (IPA/nghĩa VI).

---

## Unresolved questions

1. **Version Spring Boot cho Engly**: match 4.0.6 (đồng bộ IdP, bleeding edge) hay 3.4.x LTS-ish (ổn định)? — quyết ở phase setup.
2. **Nguồn dữ liệu IPA + nghĩa tiếng Việt**: có dataset sẵn hay cần tự curate/generate rồi verify?
3. **Số lượng động từ MVP**: ~180 (danh sách phổ biến) hay tập nhỏ hơn để ship trước?
4. **Angular version cụ thể** (v18/v19/v20) và dùng signals/standalone tới đâu — chốt ở phase setup.
