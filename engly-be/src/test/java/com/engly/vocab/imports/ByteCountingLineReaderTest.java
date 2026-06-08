package com.engly.vocab.imports;

import com.engly.vocab.imports.processing.ByteCountingLineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ByteCountingLineReader.
 *
 * Focuses on:
 *  - LF vs CRLF line termination (both handled, trailing CR/LF stripped from returned text).
 *  - Multibyte UTF-8 (CJK/emoji) byte counts exact (includes terminator bytes).
 *  - seek(offset) resumes at the correct record boundary (exact file position).
 *  - readLine() returns null at EOF.
 *
 * Uses @TempDir to avoid temp file cleanup issues.
 */
class ByteCountingLineReaderTest {

    @Test
    void readLine_stripsCrlfAndCountsFullBytes(@TempDir Path tempDir) throws Exception {
        // CRLF termination: "hello\r\n" = 7 bytes (5 + CRLF)
        Path file = tempDir.resolve("crlf.txt");
        Files.write(file, "hello\r\nworld\r\n".getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            String line1 = reader.readLine();
            assertThat(line1).isEqualTo("hello");  // CRLF stripped
            assertThat(reader.lastBytesConsumed()).isEqualTo(7);

            String line2 = reader.readLine();
            assertThat(line2).isEqualTo("world");
            assertThat(reader.lastBytesConsumed()).isEqualTo(7);

            String line3 = reader.readLine();
            assertThat(line3).isNull();
            assertThat(reader.lastBytesConsumed()).isEqualTo(0);
        }
    }

    @Test
    void readLine_lfOnly_CountsCorrectly(@TempDir Path tempDir) throws Exception {
        // LF-only termination: "hello\n" = 6 bytes (5 + LF)
        Path file = tempDir.resolve("lf.txt");
        Files.write(file, "hello\nworld\n".getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            String line1 = reader.readLine();
            assertThat(line1).isEqualTo("hello");
            assertThat(reader.lastBytesConsumed()).isEqualTo(6);

            String line2 = reader.readLine();
            assertThat(line2).isEqualTo("world");
            assertThat(reader.lastBytesConsumed()).isEqualTo(6);
        }
    }

    @Test
    void readLine_multibyteUtf8_CountsBytesExact(@TempDir Path tempDir) throws Exception {
        // CJK characters: "你好" = 6 bytes (3 bytes per char in UTF-8) + "\n" = 7 bytes
        Path file = tempDir.resolve("cjk.txt");
        Files.write(file, "你好\nemojtest😀\n".getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            String line1 = reader.readLine();
            assertThat(line1).isEqualTo("你好");
            // 你 = 3 bytes, 好 = 3 bytes, \n = 1 byte → 7 total
            assertThat(reader.lastBytesConsumed()).isEqualTo(7);

            String line2 = reader.readLine();
            assertThat(line2).isEqualTo("emojtest😀");
            // "emojtest" = 8 bytes, 😀 = 4 bytes, \n = 1 byte → 13 total
            assertThat(reader.lastBytesConsumed()).isEqualTo(13);
        }
    }

    @Test
    void seek_resumesAtExactOffset(@TempDir Path tempDir) throws Exception {
        // File: "line1\nline2\nline3\n" (6 + 6 + 6 = 18 bytes)
        Path file = tempDir.resolve("seek.txt");
        String content = "line1\nline2\nline3\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            // Read first line
            String line1 = reader.readLine();
            assertThat(line1).isEqualTo("line1");
            assertThat(reader.lastBytesConsumed()).isEqualTo(6);

            // Seek to offset 6 (start of line2)
            reader.seek(6);
            String line2 = reader.readLine();
            assertThat(line2).isEqualTo("line2");
            assertThat(reader.lastBytesConsumed()).isEqualTo(6);

            // Seek to offset 12 (start of line3)
            reader.seek(12);
            String line3 = reader.readLine();
            assertThat(line3).isEqualTo("line3");
            assertThat(reader.lastBytesConsumed()).isEqualTo(6);

            // Seek to EOF (offset 18)
            reader.seek(18);
            String eof = reader.readLine();
            assertThat(eof).isNull();
        }
    }

    @Test
    void seek_multibyteUtf8_ResumesAtCorrectBoundary(@TempDir Path tempDir) throws Exception {
        // File: "你好\nworld\n" → offsets: 你=0-3, 好=3-6, \n=6-7, world=7-12, \n=12-13
        Path file = tempDir.resolve("seek_utf8.txt");
        Files.write(file, "你好\nworld\n".getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            String line1 = reader.readLine();
            assertThat(line1).isEqualTo("你好");
            assertThat(reader.lastBytesConsumed()).isEqualTo(7);

            // Seek to offset 7 (start of line 2)
            reader.seek(7);
            String line2 = reader.readLine();
            assertThat(line2).isEqualTo("world");
            assertThat(reader.lastBytesConsumed()).isEqualTo(6);
        }
    }

    @Test
    void readLine_emptyFile_returnsNull(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.write(file, "".getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            String line = reader.readLine();
            assertThat(line).isNull();
            assertThat(reader.lastBytesConsumed()).isEqualTo(0);
        }
    }

    @Test
    void readLine_fileWithoutFinalNewline_readsCorrectly(@TempDir Path tempDir) throws Exception {
        // File without trailing newline: "hello"
        Path file = tempDir.resolve("no_final_newline.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            String line = reader.readLine();
            assertThat(line).isEqualTo("hello");
            // 5 bytes, no LF → lastBytesConsumed = 5
            assertThat(reader.lastBytesConsumed()).isEqualTo(5);

            String eof = reader.readLine();
            assertThat(eof).isNull();
        }
    }
}
