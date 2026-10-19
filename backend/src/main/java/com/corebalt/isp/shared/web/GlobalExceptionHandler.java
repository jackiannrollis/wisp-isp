package com.corebalt.isp.shared.web;

import com.corebalt.isp.shared.tenant.TenantContextMissingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem details for every error. Never leaks stack traces, SQL or exception messages
 * from infrastructure; unexpected errors are logged with the correlation ID and answered generically.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TenantContextMissingException.class)
    ProblemDetail tenantContextMissing(final TenantContextMissingException e) {
        log.warn("Request without tenant context: {}", e.getMessage());
        return ProblemResponseWriter.problem(
                HttpStatus.FORBIDDEN,
                ProblemTypes.TENANT_CONTEXT_MISSING,
                "The request has no tenant context.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(final AccessDeniedException e) {
        return ProblemResponseWriter.problem(
                HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "You do not have permission for this action.");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(final NotFoundException e) {
        return ProblemResponseWriter.problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(final ConflictException e) {
        return ProblemResponseWriter.problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, e.getMessage());
    }

    /** A unique or foreign key the request ran into; the SQL detail is logged, never returned. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityViolation(final DataIntegrityViolationException e) {
        log.info("Integrity violation: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return ProblemResponseWriter.problem(
                HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "The request conflicts with existing data.");
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(final InvalidRequestException e) {
        final ProblemDetail problem =
                ProblemResponseWriter.problem(
                        HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED, "The request is invalid.");
        problem.setProperty("errors", e.violations());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(final Exception e) {
        log.error("Unhandled exception", e);
        return ProblemResponseWriter.problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the correlation ID when reporting it.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            final MethodArgumentNotValidException ex,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final WebRequest request) {
        final ProblemDetail problem =
                ProblemResponseWriter.problem(
                        HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_FAILED, "The request is invalid.");
        problem.setProperty(
                "errors",
                ex.getBindingResult().getFieldErrors().stream()
                        .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                        .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    record FieldViolation(String field, String message) {}
}
