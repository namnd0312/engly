# Brainstorm: Import từ vựng từ CSV (GB-scale, 512MB RAM, resume được)

> Date: 2026-06-04 · Project: Engly · Module: vocab admin · Status: agreed

## 1. Problem statement

Cần chức năng admin upload CSV chứa từ vựng, đổ vào **1 deck** đã chọn. Ràng buộc:

- File có thể lớn tới **hàng GB** (defensive: thiết kế đúng cho mọi cỡ).
- Server BE chỉ **512MB RAM** → không được load cả file vào bộ nhớ.
- Process crash giữa chừng phải **resume** được, không mất/không nhân đôi dữ liệu.

## 2. Quyết định đã chốt (qua phỏng vấn)

| Vấn đề | Quyết định |
|---|---|
| Cơ chế upload | **tus** (chunked/resumable) — resume được cả khâu upload |
| Quy mô | Defensive cho mọi cỡ |
| Hạ tầng | **Chỉ Postgres** + Spring `@Async` (không Redis, không Spring Batch) |
| Checkpoint resume | **Offset** (commit cùng transaction với batch insert) |
| Gắn deck | **1 file = 1 deck**, admin chọn trước; CSV chỉ có cột từ |
| Trùng từ | Tự bỏ qua → `unique(deck_id, term)` + `ON CONFLICT DO NOTHING` |
| Dòng hỏng | Bỏ qua + ghi report (dòng số + lý do), job chạy tiếp |
| Số instance | Chưa biết → thiết kế 1 server, dùng lease pattern chừa đường scale |

## 3. Tech context hiện tại

Spring Boot 3.4.5 · Java 21 · Maven · PostgreSQL + Flyway. Chưa có async/batch/queue/object-storage.
`vocab_word(deck_id FK, term, meaning_vi, ipa, example_en, example_vi, part_of_speech, verified)`; `vocab_deck(name, slug, topic, ...)`. Hiện chỉ có POST tạo từng từ một.

## 4. Kiến trúc giải pháp (recommended)

Tách thành **2 pha độc lập** — upload và processing — nối nhau qua file bất biến trên disk.

### Pha A — Upload (tus, resumable)
- Endpoint tus `POST/PATCH/HEAD /api/admin/vocab-imports/upload` (lib `me.desair.tus:tus-java-server`, filesystem store).
- Chunk **stream thẳng xuống disk** (`/data/uploads/tmp`), **không buffer vào RAM**. tus tự resume upload qua offset.
- Upload xong → finalize thành file bất biến `/data/uploads/{uploadId}.csv`. File bất biến chính là điều khiến **offset checkpoint an toàn**.

### Pha B — Job + xử lý nền (@Async, 1 worker)
1. `POST /api/admin/vocab-imports {deckId, uploadId}` → tạo row `vocab_import_job` (status=PENDING), enqueue `@Async`.
2. Worker stream file bằng **buffered reader** (bộ nhớ hằng số) + CSV parser streaming (Apache Commons CSV / univocity), đọc theo **record**.
3. Xử lý theo **batch N record** (≈500–1000). Mỗi batch = **1 transaction**:
   - `INSERT INTO vocab_word (...) ... ON CONFLICT (deck_id, term) DO NOTHING` (JDBC batch).
   - Dòng hỏng → `INSERT vocab_import_error(job_id, line_no, raw_excerpt, reason)` (cap ~10k dòng lưu, vẫn đếm hết).
   - `UPDATE vocab_import_job SET processed_offset=<byte sau batch>, processed_count+=N, inserted_count+=affected, skipped_count+=..., heartbeat_at=now()`.
   - **commit**.
   - → Crash giữa batch: cả tx rollback (kể cả offset) → resume đọc lại đúng batch đó, không trùng (offset) + không sót, `ON CONFLICT` là lưới an toàn thứ 2.

### Memory discipline (sống còn ở 512MB)
- **JDBC `batchUpdate` thuần, KHÔNG dùng JPA `saveAll`** (tránh phình persistence context → OOM).
- JDBC URL `rewriteBatchedInserts=true`.
- Reader streaming, không bao giờ `List<toàn bộ dòng>`.
- `-Xmx` ~300–350m, chừa headroom cho JVM + disk cache.
- `@Async` executor **bounded 1–2 thread** riêng → import không bóp nghẹt API.

### Crash & resume
- **Startup `CommandLineRunner`**: quét job PENDING / RUNNING heartbeat cũ → re-enqueue.
- **`@Scheduled` sweeper** (vài phút): job RUNNING mà `heartbeat_at < now()-2min` (worker chết) → claim lại.
- **Claim bằng lease** `UPDATE ... SET worker_id, status='RUNNING', heartbeat_at=now() WHERE id=? AND status='PENDING'` (hoặc `SELECT ... FOR UPDATE SKIP LOCKED`). Chạy tốt với 1 instance, **scale lên N instance không phải sửa lại** → đáp ứng "chưa biết số server".
- Resume: seek file tới `processed_offset`, chạy tiếp.

## 5. API surface
- tus upload: `/api/admin/vocab-imports/upload`
- `POST /api/admin/vocab-imports` {deckId, uploadId} → tạo job
- `GET /api/admin/vocab-imports/{id}` → status + counts + percent (`processed_offset/file_size`) — FE poll
- `GET /api/admin/vocab-imports/{id}/errors` → report dòng hỏng (paginated)
- (optional) `POST /{id}/retry`, `DELETE /{id}`

## 6. DB changes (Flyway V5)
- `vocab_import_job` (id, deck_id, file_path, file_size, status, processed_offset, processed_count, inserted_count, skipped_count, error_count, worker_id, heartbeat_at, created_at, updated_at)
- `vocab_import_error` (id, job_id, line_no, raw_excerpt, reason, created_at)
- `CREATE UNIQUE INDEX ... ON vocab_word(deck_id, term)` — **phải dedupe dữ liệu cũ trước** khi tạo index.

## 7. Đã loại bỏ (YAGNI / brutal honesty)
- **Spring Batch**: nặng + learning curve cao, lease + checkpoint tự làm trên Postgres là đủ.
- **Redis**: không cần queue ngoài khi chỉ 1 worker; Postgres làm cả progress lẫn lease.
- **Postgres `COPY` trực tiếp**: nhanh nhất nhưng khó checkpoint mid-stream + khó skip dòng hỏng + khó `ON CONFLICT`. JDBC batch + `ON CONFLICT` đúng yêu cầu hơn. (COPY-vào-staging là hướng tăng tốc sau, không làm bây giờ.)
- **Multipart upload 1 phát**: đứt mạng = làm lại từ đầu cho file GB → tus thắng.

## 8. Rủi ro & giảm thiểu
1. **tus = dependency + learning curve mới** → dùng lib bảo trì tốt, filesystem store, là đường tách upload khỏi 512MB.
2. **Byte-offset gặp CSV có newline trong field quoted** → checkpoint tại ranh giới record của parser, hoặc enforce CSV phẳng (không newline trong field); test ở pilot.
3. **Tạo unique index trên dữ liệu cũ có thể fail nếu đã trùng** → migration dedupe trước.
4. **Lạm dụng JPA → OOM** → bắt buộc JDBC batch cho path import.
5. **Disk phải chứa file GB** → xác minh dung lượng disk server (không chỉ RAM). Dọn file temp sau khi job COMPLETED (`@Scheduled` cleanup).
6. **Transaction dài** → tuyệt đối không bọc cả file 1 tx; mỗi batch 1 tx (đã thiết kế).

## 9. Success metrics
- Import file ≥1GB / nhiều triệu dòng ở 512MB RAM **không OOM** (RSS ổn định).
- Kill process giữa chừng → khởi động lại **tự resume**, tổng `inserted+skipped+error` = số dòng file, **không bản ghi trùng**.
- Upload đứt mạng → client resume tiếp, không upload lại từ đầu.
- Dòng hỏng không làm chết job; xem được report.

## 10. Unresolved questions
- Định dạng CSV chốt: có header không? thứ tự/tên cột? delimiter (`,` hay `;`/`\t`)? encoding (UTF-8)?
- Field nào bắt buộc tối thiểu? (đoán: `term` + `meaning_vi`; còn lại optional)
- `verified` khi import mặc định `false`, hay cho phép set trong CSV?
- Disk server thực tế bao nhiêu GB? (quyết định cap kích thước file)
- Mount `/data/uploads` có **persistent** qua restart/redeploy không? (resume sau crash phụ thuộc file còn trên disk)
- Có cần huỷ (cancel) job đang chạy không?
