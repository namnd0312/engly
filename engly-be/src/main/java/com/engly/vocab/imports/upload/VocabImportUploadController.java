package com.engly.vocab.imports.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.desair.tus.server.TusFileUploadService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Thin delegate for the tus protocol verbs. tus drives the full handshake itself
 * (Upload-Offset / Upload-Length / status codes) via {@code process()} — this controller
 * only forwards POST (create), PATCH (chunk), HEAD (offset), DELETE (abort) for both the
 * base path and the {@code /{uploadId}} sub-path tus appends on PATCH/HEAD/DELETE.
 *
 * Content-type is {@code application/offset+octet-stream}, so Spring's multipart resolver
 * (which only triggers on {@code multipart/*}) never intercepts the body. No multipart filter
 * is registered on this path — chunks stream straight to tus's disk store.
 */
@RestController
public class VocabImportUploadController {

    private final TusFileUploadService tusService;

    public VocabImportUploadController(TusFileUploadService tusService) {
        this.tusService = tusService;
    }

    @RequestMapping(
            value = {TusUploadConfig.UPLOAD_URI, TusUploadConfig.UPLOAD_URI + "/**"},
            method = {RequestMethod.POST, RequestMethod.PATCH, RequestMethod.HEAD,
                    RequestMethod.DELETE, RequestMethod.GET, RequestMethod.OPTIONS})
    public void upload(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // tus maps protocol violations to status codes internally; process() only surfaces IOException.
        tusService.process(request, response);
    }
}
