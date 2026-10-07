package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps exceptions to HTTP responses that share one body shape: {@code error}, {@code message},
 * {@code timestamp} (plus extra fields in a few cases). Framework errors such as an unknown path,
 * a wrong HTTP method, a bad parameter type or a duplicate key get their 4xx status, not a 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static Map<String, Object> body(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Not Found",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation Error",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(CircularDependencyException.class)
    public ResponseEntity<Map<String, Object>> handleCircularDependency(CircularDependencyException ex) {
        log.warn("Circular dependency detected: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Circular Dependency",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(LockAlreadyHeldException.class)
    public ResponseEntity<Map<String, Object>> handleLockAlreadyHeld(LockAlreadyHeldException ex) {
        log.warn("Lock already held: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Lock Conflict",
                "message", ex.getMessage(),
                "lockedByUserId", ex.getLockedByUserId() != null ? ex.getLockedByUserId() : "",
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(NoActiveLockException.class)
    public ResponseEntity<Map<String, Object>> handleNoActiveLock(NoActiveLockException ex) {
        log.warn("No active lock: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "No Active Lock",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Forbidden",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(DirectoryLookupException.class)
    public ResponseEntity<Map<String, Object>> handleDirectoryLookup(DirectoryLookupException ex) {
        log.warn("Directory lookup ({}): {}", ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", ex.getErrorCode(),
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        log.warn("Onboard rejected — user already exists: {} (active={})", ex.getUserId(), ex.isActive());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.isActive() ? "user_exists" : "user_inactive",
                "message", ex.getMessage(),
                "userId", ex.getUserId() != null ? ex.getUserId() : "",
                "timestamp", Instant.now().toString()
        ));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value"
                ))
                .collect(Collectors.toList());

        log.warn("Validation failed: {}", errors);
        Map<String, Object> body = body("Validation Error", errors.stream()
                .map(error -> error.get("message"))
                .collect(Collectors.joining("; ")));
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.badRequest()
                .body(body("Bad Request", "Required parameter '" + ex.getParameterName() + "' is missing"));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(body("Bad Request", "Invalid request format. Please check your input and try again."));
    }

    /**
     * Every other framework exception Spring MVC handles (unknown path 404, wrong method 405,
     * unsupported media type 415, ...): keep its status, use the shared body, log at WARN.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof Map<?, ?>) {
            return super.handleExceptionInternal(ex, body, headers, statusCode, request);
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = status != null ? status.getReasonPhrase() : "Error";
        log.warn("{} {}: {}", statusCode.value(), reason, ex.getMessage());
        return ResponseEntity.status(statusCode).headers(headers).body(body(reason, safeMessage(statusCode)));
    }

    private static String safeMessage(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 404 -> "The requested resource was not found.";
            case 405 -> "This HTTP method is not supported for this resource.";
            case 406 -> "The requested response format is not supported.";
            case 415 -> "The request content type is not supported.";
            default -> "The request could not be processed.";
        };
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Bad parameter type for '{}'", ex.getName());
        return ResponseEntity.badRequest()
                .body(body("Bad Request", "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", message);
        return ResponseEntity.badRequest().body(body("Validation Error", message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("Conflict", "The change conflicts with existing data."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Forbidden",
                "message", "You do not have permission to perform this action. Required role: ADMIN or ENV_ADMIN.",
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "An unexpected error occurred. Please try again or contact support.",
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unexpected exception: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "An unexpected error occurred. Please try again or contact support.",
                "timestamp", Instant.now().toString()
        ));
    }
}

