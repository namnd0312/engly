package com.engly.vocab.imports.upload;

import me.desair.tus.server.TusFileUploadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the tus resumable-upload service as a disk-backed endpoint under /api/admin/vocab-imports/upload.
 *
 * Two directories under the configured storage root:
 *  - {root}/tus      → tus's internal chunk store (in-progress uploads, buffered to DISK not RAM).
 *  - {root}/imports  → finalized immutable CSV files the async processor streams from.
 *
 * The upload endpoint is owned by Security Chain 1 (/api/admin/**): ROLE_ADMIN, stateless Bearer,
 * CSRF disabled — correct for tus's raw PATCH/HEAD verbs.
 */
@Configuration
public class TusUploadConfig {

    /** Public path tus uploads are addressed at; must match the controller's @RequestMapping. */
    public static final String UPLOAD_URI = "/api/admin/vocab-imports/upload";

    @Value("${upload.storage.path:/data/uploads}")
    private String storageRoot;

    /** Cap disk-exhaustion DoS; 2GB matches the validated max (Tomcat limits aligned in application.yml). */
    @Value("${upload.max-size:2147483648}")
    private long maxUploadSize;

    @Bean
    public TusFileUploadService tusFileUploadService() {
        String tusPath = Paths.get(storageRoot, "tus").toString();
        File dir = new File(tusPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create tus storage directory: " + tusPath);
        }
        return new TusFileUploadService()
                .withStoragePath(tusPath)
                .withUploadUri(UPLOAD_URI)
                .withMaxUploadSize(maxUploadSize);
    }

    /** Immutable finalized-file directory; the processor reads from here, cleanup deletes from here. */
    @Bean
    public Path importFileDirectory() {
        Path dir = Paths.get(storageRoot, "imports").toAbsolutePath().normalize();
        File f = dir.toFile();
        if (!f.exists() && !f.mkdirs()) {
            throw new IllegalStateException("Cannot create import file directory: " + dir);
        }
        return dir;
    }
}
