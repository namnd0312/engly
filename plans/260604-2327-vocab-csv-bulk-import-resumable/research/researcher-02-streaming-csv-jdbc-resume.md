# Research: Streaming CSV + JDBC Resume w/ Byte-Offset Checkpointing

**Date:** 2026-06-04  
**Context:** Spring Boot 3.4.5 / Java 21 / PostgreSQL on 512MB RAM  
**Token Budget:** ~150 lines, concise

---

## 1. Streaming CSV Parsers: Apache Commons CSV vs univocity-parsers

### Apache Commons CSV
- **Iterator API:** `CSVFormat.parse(Reader)` returns `CSVParser` (implements `Iterable<CSVRecord>`)
- **True Streaming:** Yes—doesn't buffer entire file; reads per-record on iteration
- **Quoted Fields w/ Newlines:** Full support via internal state machine
- **Byte Position Tracking:** **NO native support**—returns `CSVRecord` with no offset metadata
- **Maven:** `org.apache.commons:commons-csv:1.11.0`

### univocity-parsers
- **Iterator API:** `CsvParser.iterate(Reader)` or `parseAll(Reader)` with `recordMetaData()` callback
- **True Streaming:** Yes—event-driven, zero line buffering when using `iterate()`
- **Quoted Fields w/ Newlines:** Full support
- **Byte Position Tracking:** **YES—`recordMetaData.recordStartPos()`** gives char position (not byte); separate `getParsingContext().currentParsedContent()` for context
- **Maven:** `com.univocity:univocity-parsers:2.9.1`

**Recommendation:** **univocity-parsers** for native position tracking; acceptable caveat: position is char-based, not byte-based (see byte-offset trap below).

---

## 2. THE BYTE-OFFSET TRAP: Accurate Resumable Position

### The Problem
- `BufferedReader` / CSV parsers read ahead (often 8KB buffer)
- `FileChannel.position()` reports OS position AFTER the buffer, not the record being processed
- Resume from `FileChannel.position()` skips or re-reads records

### Solutions Ranked

| Approach | Pros | Cons | Recommended |
|----------|------|------|-------------|
| **CountingInputStream + flush per-record** | Accurate byte count; simple | Parser must flush internal buffers per-record; slight perf cost | For strict byte-accuracy |
| **Manual line-reading + counting** | Full control; no buffer mystery | Reinvent CSV parsing (fragile with quoted newlines) | ❌ Avoid |
| **Measure at batch boundary + ON CONFLICT idempotency** | No parser changes; avoids byte trap; relies on DB dedup | Loses granular resume (coarser checkpoints); assumes ON CONFLICT works correctly | ✅ **Recommended for 512MB** |

### Recommended Approach: Batch-Boundary Checkpoint + Idempotency

**Rationale:** 512MB RAM is tight. Avoid per-record position tracking overhead.

**Strategy:**
1. Read N records (e.g., 1000) into batch
2. After successful batch INSERT, checkpoint: `UPDATE vocab_import_job SET processed_offset = current_position WHERE job_id = ?`
3. On resume, seek to offset using **RandomAccessFile** or **FileChannel**, re-open stream at that position
4. Insert again—ON CONFLICT (deck_id, term) DO NOTHING deduplicates silently

**Byte Position Capture (batch boundary):**
```java
// After flushing batch, capture position
BytePosition checkpoint = countingInputStream.getByteCount();
// Alternatively: sum += records.size(); use record count as logical offset (not byte)
```

**Resume: RandomAccessFile.seek()**
```java
RandomAccessFile raf = new RandomAccessFile(csvFile, "r");
raf.seek(checkpointByteOffset);  // ⚠️  assumes 1 char = 1 byte (UTF-8); verify charset
InputStream is = new FileInputStream(raf.getFD());
CSVParser parser = CSVFormat.DEFAULT.parse(new InputStreamReader(is, StandardCharsets.UTF_8));
```

**Issue with byte vs. char:** UTF-8 is variable-width. Safe approach: count via `CountingInputStream` wrapping UTF-8 stream, or track record count (logical offset) instead of byte offset. For ASCII-only CSVs, byte = char.

---

## 3. JDBC Batch Insert w/ PostgreSQL ON CONFLICT

### Code Skeleton
```java
String sql = "INSERT INTO vocab_word (deck_id, term, definition, created_at) " +
             "VALUES (?, ?, ?, NOW()) " +
             "ON CONFLICT (deck_id, term) DO NOTHING";

int[] counts = jdbcTemplate.batchUpdate(sql, 
  new BatchPreparedStatementSetter() {
    @Override
    public void setValues(PreparedStatement ps, int i) throws SQLException {
      VocabWord word = batch.get(i);
      ps.setLong(1, word.getDeckId());
      ps.setString(2, word.getTerm());
      ps.setString(3, word.getDefinition());
    }
    @Override
    public int getBatchSize() { return batch.size(); }
  });

// counts[i] = affected rows for statement i
// With ON CONFLICT DO NOTHING: may return 0 (inserted 0) or 1 (inserted 1); -2 if unknown
int totalInserted = Arrays.stream(counts).sum();  // ⚠️  May be -2 with pgjdbc + ON CONFLICT
```

### reWriteBatchedInserts Parameter

**Critical Caveat:** `reWriteBatchedInserts=true` rewrites `INSERT VALUES (?)...VALUES (?)` into `INSERT VALUES (...), (...), (...)` for 2-3x speedup **BUT does NOT apply to ON CONFLICT clauses**. When ON CONFLICT is present, JDBC executes each statement individually.

**JDBC URL with reWriteBatchedInserts:**
```
jdbc:postgresql://localhost:5432/engly?reWriteBatchedInserts=true
```

**Recommendation:** Keep `reWriteBatchedInserts=true` for non-CONFLICT batches. For CONFLICT batches, accept individual execute; ON CONFLICT idempotency absorbs duplicates.

### Counting Actually-Inserted Rows

With `ON CONFLICT DO NOTHING`:
- pgjdbc returns `int[] counts` where `counts[i]` = rows affected by statement i
- Affected = 1 if row inserted, 0 if row existed (conflict), -2 if unknown
- **Don't rely on sum for accuracy**; use ON CONFLICT as dedup, not as truth

**Workaround for precise count:**
```java
// Option A: Use RETURNING clause (works with ON CONFLICT)
String sql = "INSERT INTO ... ON CONFLICT ... DO NOTHING RETURNING id";
PreparedStatement ps = conn.prepareStatement(sql);
// ... set values ...
ResultSet rs = ps.executeQuery();
int insertedCount = 0;
while (rs.next()) insertedCount++;
```

---

## 4. Atomic Checkpoint: INSERT + UPDATE Offset in One Transaction

### Spring TransactionTemplate Pattern
```java
@Autowired private TransactionTemplate transactionTemplate;
@Autowired private JdbcTemplate jdbcTemplate;

public void importBatchAtomic(List<VocabWord> batch, long jobId, long processedOffset) {
  transactionTemplate.executeWithoutResult(status -> {
    // INSERT batch
    int[] counts = jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() { ... });
    
    // UPDATE checkpoint (same transaction)
    jdbcTemplate.update(
      "UPDATE vocab_import_job SET processed_offset = ?, updated_at = NOW() WHERE id = ?",
      processedOffset, jobId
    );
    // If exception → rollback both; else commit both
  });
}
```

### Why Plain JDBC > JPA saveAll
- **JPA:** Each `save()` adds entity to persistence context → memory bloat (O(n) RAM for batch size n)
- **Plain JDBC:** Statement executes, results discarded → O(1) RAM
- **For 512MB:** Critical difference with 10K+ record batches

---

## 5. Bounded @Async Executor for CSV Import

### ThreadPoolTaskExecutor Config
```java
@Configuration
@EnableAsync
public class AsyncConfig {
  @Bean(name = "csvImportExecutor")
  public ThreadPoolTaskExecutor csvImportExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(1);
    ex.setMaxPoolSize(2);  // 512MB → single worker; 2nd for queued backup
    ex.setQueueCapacity(2);  // Small queue to avoid memory bloat
    ex.setRejectedExecutionHandler(new ThreadPoolTaskExecutor.CallerRunsPolicy());
    // CallerRunsPolicy: main thread executes if executor queue full
    ex.setThreadNamePrefix("csv-import-");
    ex.initialize();
    return ex;
  }
}

@Service
public class VocabImportService {
  @Async("csvImportExecutor")
  public void importCsvAsync(String filePath, long jobId) { ... }
}
```

### JVM Memory Settings for 512MB Container
```bash
# Dockerfile / startup
java -Xmx384m -Xms256m \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -jar app.jar
```
- **-Xmx384m:** 384MB heap (leaving 128MB for OS, NIO buffers)
- **G1GC:** Low-pause, suitable for streaming workloads
- **Async pool:** 1-2 threads; CSV parsing is I/O-bound, not CPU-bound

---

## Maven Coordinates Summary

```xml
<!-- Streaming CSV (univocity, recommended) -->
<dependency>
  <groupId>com.univocity</groupId>
  <artifactId>univocity-parsers</artifactId>
  <version>2.9.1</version>
</dependency>

<!-- Byte counting (if strict byte-offset needed) -->
<dependency>
  <groupId>commons-io</groupId>
  <artifactId>commons-io</artifactId>
  <version>2.15.1</version>
</dependency>

<!-- Spring JDBC (already in Boot) -->
<!-- Spring Data JPA (already in Boot) -->
```

---

## Unresolved Questions

1. **UTF-8 multi-byte chars in CSV:** If term contains emoji/CJK, byte offset ≠ char offset. Workaround: track record count, not byte offset; or validate charset uniformity.
2. **RETURNING clause with pgjdbc:** Does `ps.executeQuery()` + RETURNING work reliably with ON CONFLICT in all pgjdbc versions ≥42.x? Verify in integration test.
3. **Batch size tuning:** For 512MB, is 1000 records/batch safe, or should we reduce to 100? Depends on record size; requires profiling.
4. **Flyway migration for vocab_word table:** Ensure unique constraint `(deck_id, term)` exists BEFORE import job runs; otherwise ON CONFLICT silently fails.

---

## Sources

- [A Dissection of Java JDBC to PostgreSQL, Part 2: Batching](https://foojay.io/today/a-dissection-of-java-jdbc-to-postgresql-connections-part-2-batching/)
- [PostgreSQL reWriteBatchedInserts - Vlad Mihalcea](https://vladmihalcea.com/postgresql-multi-row-insert-rewritebatchedinserts-property/)
- [Bulk Update/Upserts with Spring Data JDBC](https://blog.cloudneutral.se/bulk-updateupserts-with-spring-data-jdbc)
- [Spring JDBC Batch Inserts | Baeldung](https://www.baeldung.com/spring-jdbc-batch-inserts)
- [Apache Commons CSV User Guide](https://commons.apache.org/proper/commons-csv/user-guide.html)
- [CountingInputStream API](https://commons.apache.org/proper/commons-io/apidocs/org/apache/commons/io/input/CountingInputStream.html)
