package com.engly.vocab.imports.processing;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Streaming line reader that tracks the EXACT number of file bytes consumed per line.
 *
 * This is the crux of crash-resumable byte-offset checkpoints. A normal BufferedReader reads
 * ahead, so {@code channel.position()} would over-report and a stored offset would skip data on
 * resume. Here we count raw bytes ourselves and seek with {@link #seek(long)}, so the cumulative
 * offset is always a TRUE file position pointing right after a record-terminating '\n'.
 *
 * Counts raw bytes (not chars) → multibyte UTF-8 (CJK/emoji) is safe. CRLF and LF both handled:
 * the trailing '\r'/'\n' are stripped from the returned text but fully counted in bytesConsumed.
 *
 * NOT thread-safe; one reader per processing run.
 */
public final class ByteCountingLineReader implements Closeable {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final byte LF = (byte) '\n';
    private static final byte CR = (byte) '\r';

    private final FileChannel channel;
    private final byte[] buf = new byte[BUFFER_SIZE];
    private final ByteBuffer bb = ByteBuffer.wrap(buf);
    private int pos = 0;     // next unread index in buf
    private int limit = 0;   // number of valid bytes currently in buf
    private boolean eof = false;

    private byte[] lineBytes = new byte[256];
    private long lastBytesConsumed = 0;

    public ByteCountingLineReader(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    /** Reposition to an absolute byte offset (a previously checkpointed record boundary). */
    public void seek(long offset) throws IOException {
        channel.position(offset);
        pos = 0;
        limit = 0;
        eof = false;
    }

    /** Exact raw file bytes consumed by the most recent {@link #readLine()} (incl. terminator). */
    public long lastBytesConsumed() {
        return lastBytesConsumed;
    }

    /**
     * Read one line. Returns the decoded text WITHOUT its trailing CR/LF, or null at EOF.
     * After a non-null return, {@link #lastBytesConsumed()} gives the exact bytes advanced.
     */
    public String readLine() throws IOException {
        int len = 0;
        boolean readAny = false;
        boolean foundLf = false;

        while (true) {
            if (pos >= limit && !fill()) {
                break; // EOF
            }
            readAny = true;
            byte b = buf[pos++];
            if (b == LF) {
                foundLf = true;
                break;
            }
            if (len >= lineBytes.length) {
                lineBytes = Arrays.copyOf(lineBytes, lineBytes.length * 2);
            }
            lineBytes[len++] = b;
        }

        if (!readAny) {
            lastBytesConsumed = 0;
            return null;
        }

        lastBytesConsumed = (long) len + (foundLf ? 1 : 0);
        // Strip a trailing CR (CRLF) from the text; it remains counted in lastBytesConsumed.
        int textLen = (len > 0 && lineBytes[len - 1] == CR) ? len - 1 : len;
        return new String(lineBytes, 0, textLen, StandardCharsets.UTF_8);
    }

    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        bb.clear();
        int n = channel.read(bb);
        if (n <= 0) {
            eof = true;
            return false;
        }
        pos = 0;
        limit = n;
        return true;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
