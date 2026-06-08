package com.engly.vocab.imports;

import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import com.engly.vocab.imports.processing.VocabImportProcessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory efficiency test for vocab imports.
 *
 * Verifies that a large CSV (200,000+ valid rows) completes without OOM
 * under a capped heap (-Xmx200m via surefire configuration).
 *
 * The processor is designed to hold only one batch of rows in memory at a time,
 * so heap usage should remain bounded regardless of file size.
 *
 * This test is tagged @Tag("memory") so it can be run separately with:
 *   ./mvnw test -Dgroups=memory
 * Or excluded from default runs via:
 *   ./mvnw test -DexcludedGroups=memory
 *
 * Run with memory test tag:
 *   ./mvnw test -Dgroups=memory -DargLine="-Xmx200m"
 */
@Tag("memory")
class VocabImportMemoryTest extends AbstractVocabImportIntegrationTest {

    @Autowired
    private VocabImportRepository repo;

    @Autowired
    private VocabImportProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void largeImport_200kRows_completesWithoutOom(@TempDir Path tempDir) throws Exception {
        long startMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long maxMem = Runtime.getRuntime().maxMemory();

        Long deckId = createTestDeck("memory-test-deck");

        // Generate 200,000 valid rows
        int rowCount = 200_000;
        List<VocabImportCsvFixture.CsvRow> rows = VocabImportCsvFixture.generateValidRows(rowCount);
        Path csvFile = VocabImportCsvFixture.writeCsv(tempDir, rows);

        long jobId = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));

        // Track memory usage
        long peakMem = startMem;
        long sampleCount = 0;

        // Trigger GC before start
        System.gc();

        // Run processor
        processor.process(jobId);

        // Poll until completion (with memory sampling every 500ms)
        long deadline = System.currentTimeMillis() + (120 * 1000);  // 2 min timeout
        while (System.currentTimeMillis() < deadline) {
            var job = repo.findById(jobId);
            if (job.isPresent()) {
                var status = job.get().status();
                if (status.name().equals("COMPLETED") || status.name().equals("FAILED")) {
                    break;
                }
            }
            // Sample memory usage
            long currentMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            if (currentMem > peakMem) {
                peakMem = currentMem;
            }
            sampleCount++;
            Thread.sleep(100);
        }

        var jobFinal = repo.findById(jobId).get();
        assertThat(jobFinal.status().name()).isEqualTo("COMPLETED");
        assertThat(jobFinal.insertedCount()).isEqualTo(rowCount);

        // Verify no loss
        Long vocabCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(vocabCount).isEqualTo(rowCount);

        // Log memory usage for diagnostics
        long endMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long peakMb = peakMem / (1024 * 1024);
        long maxMb = maxMem / (1024 * 1024);
        System.out.println("Memory test: 200k rows, peak=" + peakMb + "MB, max=" + maxMb + "MB, samples=" + sampleCount);

        // Verify no duplicates
        Long dupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT deck_id, term FROM vocab_word " +
                "WHERE deck_id = ? GROUP BY deck_id, term HAVING count(*) > 1) t",
                Long.class, deckId);
        assertThat(dupCount).isEqualTo(0);
    }

    // --- helpers ---

    private Long createTestDeck(String slug) {
        return jdbc.queryForObject(
                "INSERT INTO vocab_deck (name, slug) VALUES (?, ?) RETURNING id",
                Long.class, "Test Deck " + slug, slug);
    }
}
