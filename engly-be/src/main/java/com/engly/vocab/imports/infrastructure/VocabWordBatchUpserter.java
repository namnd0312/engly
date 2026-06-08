package com.engly.vocab.imports.infrastructure;

import com.engly.vocab.imports.domain.ParsedRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and executes a single multi-row VALUES upsert per batch (plain JDBC, no JPA).
 *
 * Design (OOM-safe at 512MB, fast, idempotent):
 *  - ONE INSERT statement with N value tuples → minimal round-trips, bounded params
 *    (~500 rows × 7 params ≈ 3500, well under pgjdbc's 32767 limit).
 *  - ON CONFLICT (deck_id, term) DO NOTHING → re-running an import never duplicates;
 *    the returned affected-row count is exactly the number of NEW rows inserted.
 *  - In-batch de-dup by term BEFORE binding (last-wins) → avoids same-statement self-conflict.
 *  - `verified` is a literal false (never a param) — CSV can never set it.
 *
 * NOTE: reWriteBatchedInserts is deliberately NOT relied upon (it does not merge ON CONFLICT).
 */
@Component
public class VocabWordBatchUpserter {

    private static final String COLUMNS =
            "INSERT INTO vocab_word (deck_id,term,meaning_vi,ipa,example_en,example_vi,part_of_speech,verified) VALUES ";
    private static final String CONFLICT = " ON CONFLICT (deck_id,term) DO NOTHING";
    private static final String TUPLE = "(?,?,?,?,?,?,?,false)";

    private final JdbcTemplate jdbcTemplate;

    public VocabWordBatchUpserter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Build the multi-row INSERT SQL for {@code rowCount} tuples. Package-visible for unit testing. */
    static String buildSql(int rowCount) {
        StringBuilder sb = new StringBuilder(COLUMNS.length() + rowCount * (TUPLE.length() + 1) + CONFLICT.length());
        sb.append(COLUMNS);
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) sb.append(',');
            sb.append(TUPLE);
        }
        sb.append(CONFLICT);
        return sb.toString();
    }

    /**
     * De-dup by term then upsert in one statement.
     * @return number of rows actually inserted (conflicts + in-batch dupes are not counted).
     */
    public int upsertBatch(Long deckId, List<ParsedRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        // Last-wins de-dup keyed by term (collapses in-batch duplicates → counted as skipped by caller).
        Map<String, ParsedRow> unique = new LinkedHashMap<>(rows.size() * 2);
        for (ParsedRow r : rows) {
            unique.put(r.term(), r);
        }
        List<ParsedRow> deduped = List.copyOf(unique.values());

        String sql = buildSql(deduped.size());
        return jdbcTemplate.update(sql, ps -> {
            int p = 1;
            for (ParsedRow r : deduped) {
                ps.setLong(p++, deckId);
                ps.setString(p++, r.term());
                ps.setString(p++, r.meaningVi());
                ps.setString(p++, r.ipa());
                ps.setString(p++, r.exampleEn());
                ps.setString(p++, r.exampleVi());
                ps.setString(p++, r.partOfSpeech());
            }
        });
    }
}
