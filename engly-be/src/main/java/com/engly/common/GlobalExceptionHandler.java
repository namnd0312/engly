package com.engly.common;

import com.engly.identity.domain.EmailAlreadyExistsException;
import com.engly.identity.domain.EmailNotVerifiedException;
import com.engly.identity.domain.InvalidAvatarException;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.InvalidMfaCodeException;
import com.engly.identity.domain.MfaAlreadyEnrolledException;
import com.engly.identity.domain.MfaLockedException;
import com.engly.identity.domain.MfaNotEnrolledException;
import com.engly.identity.domain.MfaUnavailableException;
import com.engly.identity.domain.InvalidVerificationTokenException;
import com.engly.verb.domain.VerbNotFoundException;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.domain.VocabWordNotFoundException;
import com.engly.vocab.imports.domain.VocabImportConflictException;
import com.engly.vocab.imports.domain.VocabImportJobNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Centralized exception handler — maps domain/validation exceptions to safe HTTP responses.
 * Never leaks stack traces or SQL details to the client.
 * Covers verbs, vocab decks, and vocab words; add new domain exceptions here as modules grow.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // H1: add SLF4J logger so unhandled exceptions are visible in prod logs
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VerbNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVerbNotFound(VerbNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(VocabDeckNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDeckNotFound(VocabDeckNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(VocabWordNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWordNotFound(VocabWordNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(VocabImportJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleImportJobNotFound(VocabImportJobNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // Import conflicts: upload not complete / retry on a non-FAILED job → 409.
    @ExceptionHandler(VocabImportConflictException.class)
    public ResponseEntity<Map<String, Object>> handleImportConflict(VocabImportConflictException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Self-service sign-up with an email that already has an account → 409.
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleEmailExists(EmailAlreadyExistsException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Rejected avatar operation (bad content type, foreign/malformed key, missing upload) → 400.
    @ExceptionHandler(InvalidAvatarException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidAvatar(InvalidAvatarException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Failed password login or invalid/expired refresh token → 401. Message stays generic.
    @ExceptionHandler(InvalidLoginException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidLogin(InvalidLoginException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    // Valid credentials but the account has not clicked its activation link → 403. Carries a
    // machine-readable `code` so the SPA can branch to the "resend activation email" action.
    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<Map<String, Object>> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage(), "EMAIL_NOT_VERIFIED");
    }

    // TOTP step-up failures. Each carries a machine-readable `code` the SPA branches on.
    @ExceptionHandler(InvalidMfaCodeException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidMfaCode(InvalidMfaCodeException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_MFA_CODE");
    }

    @ExceptionHandler(MfaLockedException.class)
    public ResponseEntity<Map<String, Object>> handleMfaLocked(MfaLockedException ex) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), "MFA_LOCKED");
    }

    @ExceptionHandler(MfaUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleMfaUnavailable(MfaUnavailableException ex) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), "MFA_UNAVAILABLE");
    }

    @ExceptionHandler(MfaNotEnrolledException.class)
    public ResponseEntity<Map<String, Object>> handleMfaNotEnrolled(MfaNotEnrolledException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), "MFA_NOT_ENROLLED");
    }

    @ExceptionHandler(MfaAlreadyEnrolledException.class)
    public ResponseEntity<Map<String, Object>> handleMfaAlreadyEnrolled(MfaAlreadyEnrolledException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), "MFA_ALREADY_ENROLLED");
    }

    // Activation token unknown/expired/already used → 400.
    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidVerificationToken(InvalidVerificationTokenException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    // M3: strip method-path prefix (e.g. "verbQueryService.search.size:") from constraint messages
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(cv -> {
                    String path = cv.getPropertyPath().toString();
                    // Keep only the leaf parameter name (after last dot), not the full method path
                    String leafName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return leafName + ": " + cv.getMessage();
                })
                .findFirst()
                .orElse("Invalid request parameters");
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    // Unique constraint violation on admin create/update → 409. Generic message covers all resources
    // (verb base_form, deck slug, etc.). No SQL/stacktrace leak.
    // NOTE: verb integration test asserting "base_form already exists" must be updated in P06.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return buildResponse(HttpStatus.CONFLICT, "resource already exists");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        // H1: log full exception server-side; return generic message to client (no stack trace)
        log.error("Unhandled exception", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        );
        return ResponseEntity.status(status).body(body);
    }

    // Overload for the one case that needs a machine-readable `code` — keeps the 4-key shape
    // unchanged for every other handler (FE only parses `message` elsewhere).
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message, String code) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "code", code
        );
        return ResponseEntity.status(status).body(body);
    }
}
