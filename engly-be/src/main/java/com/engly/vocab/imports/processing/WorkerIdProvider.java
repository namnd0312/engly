package com.engly.vocab.imports.processing;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Supplies a stable per-instance worker id (hostname + a startup-random UUID), used as the
 * lease owner written to vocab_import_job.worker_id. Distinct per JVM start so a restarted
 * instance never collides with the lease of a previous (crashed) run. Capped to 100 chars
 * to fit the worker_id column.
 */
@Component
public class WorkerIdProvider {

    private final String workerId;

    public WorkerIdProvider() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        String id = host + ":" + UUID.randomUUID();
        this.workerId = id.length() > 100 ? id.substring(0, 100) : id;
    }

    public String workerId() {
        return workerId;
    }
}
