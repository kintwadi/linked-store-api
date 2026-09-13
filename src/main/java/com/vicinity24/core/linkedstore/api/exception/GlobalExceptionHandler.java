package com.vicinity24.core.linkedstore.api.exception;

import com.vicinity24.core.linkedstore.api.dto.ApiErrorResponse;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ReservationExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleReservationExpired(
            ReservationExpiredException ex, HttpServletRequest request) {
        log.warn("Reservation expired: tx={}, lock={}", ex.getTransactionId(), ex.getInventoryLockId());
        Map<String, Object> details = new HashMap<>();
        if (ex.getTransactionId() != null) {
            details.put("transaction_id", ex.getTransactionId());
        }
        if (ex.getInventoryLockId() != null) {
            details.put("inventory_lock_id", ex.getInventoryLockId());
        }
        if (ex.getExpiredAt() != null) {
            details.put("expired_at", ex.getExpiredAt());
        }
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.GONE.value())
                .error("Reservation Expired")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("RESERVATION_EXPIRED")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @ExceptionHandler(InventoryLockFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleInventoryLockFailed(
            InventoryLockFailedException ex, HttpServletRequest request) {
        log.warn("Inventory lock failed: variant={}, requested={}, available={}",
                ex.getVariantId(), ex.getRequestedQuantity(), ex.getAvailableQuantity());
        Map<String, Object> details = new HashMap<>();
        if (ex.getVariantId() != null) {
            details.put("variant_id", ex.getVariantId());
        }
        if (ex.getRequestedQuantity() != null) {
            details.put("requested_quantity", ex.getRequestedQuantity());
        }
        if (ex.getAvailableQuantity() != null) {
            details.put("available_quantity", ex.getAvailableQuantity());
        }
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Inventory Lock Failed")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("INVENTORY_LOCK_FAILED")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidQrTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidQrToken(
            InvalidQrTokenException ex, HttpServletRequest request) {
        log.warn("Invalid QR token: reason={}, token={}", ex.getRejectReason(), ex.getSecureToken());
        Map<String, Object> details = new HashMap<>();
        details.put("reject_reason", ex.getRejectReason());
        if (ex.getSecureToken() != null) {
            details.put("secure_token_prefix",
                    ex.getSecureToken().length() > 8
                            ? ex.getSecureToken().substring(0, 8) + "..."
                            : ex.getSecureToken());
        }
        HttpStatus status = switch (ex.getRejectReason()) {
            case TOKEN_ALREADY_SCANNED -> HttpStatus.CONFLICT;
            case TOKEN_EXPIRED -> HttpStatus.GONE;
            case UNAUTHORIZED_SCANNER -> HttpStatus.FORBIDDEN;
            case TRANSACTION_NOT_IN_PAID_STATE -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(status.value())
                .error("QR Token " + ex.getRejectReason())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("QR_" + ex.getRejectReason())
                .details(details)
                .build();
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientStock(
            InsufficientStockException ex, HttpServletRequest request) {
        log.warn("Insufficient stock: variant={}, requested={}, available={}",
                ex.getVariantId(), ex.getRequested(), ex.getAvailable());
        Map<String, Object> details = new HashMap<>();
        details.put("variant_id", ex.getVariantId());
        details.put("requested_quantity", ex.getRequested());
        details.put("available_quantity", ex.getAvailable());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Insufficient Stock")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("INSUFFICIENT_STOCK")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(TransactionStateException.class)
    public ResponseEntity<ApiErrorResponse> handleTransactionState(
            TransactionStateException ex, HttpServletRequest request) {
        log.warn("Transaction state error: tx={}, current={}, expected={}",
                ex.getTransactionId(), ex.getCurrentStatus(), ex.getExpectedStatus());
        Map<String, Object> details = new HashMap<>();
        details.put("transaction_id", ex.getTransactionId());
        details.put("current_status", ex.getCurrentStatus());
        details.put("expected_status", ex.getExpectedStatus());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Invalid Transaction State")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("TRANSACTION_STATE_INVALID")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RoleNotAuthorizedException.class)
    public ResponseEntity<ApiErrorResponse> handleRoleNotAuthorized(
            RoleNotAuthorizedException ex, HttpServletRequest request) {
        log.warn("Role not authorized: user={}, actual={}, allowed={}",
                ex.getUserId(), ex.getActualRole(), ex.getAllowedRoles());
        Map<String, Object> details = new HashMap<>();
        details.put("user_id", ex.getUserId());
        details.put("actual_role", ex.getActualRole());
        details.put("allowed_roles", ex.getAllowedRoles());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Role Not Authorized")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("ROLE_NOT_AUTHORIZED")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(StripePaymentFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleStripePaymentFailed(
            StripePaymentFailedException ex, HttpServletRequest request) {
        log.error("Stripe payment failed: code={}, decline={}", ex.getStripeErrorCode(), ex.getDeclineCode(), ex);
        Map<String, Object> details = new HashMap<>();
        if (ex.getStripeErrorCode() != null) {
            details.put("stripe_error_code", ex.getStripeErrorCode());
        }
        if (ex.getDeclineCode() != null) {
            details.put("decline_code", ex.getDeclineCode());
        }
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.PAYMENT_REQUIRED.value())
                .error("Payment Failed")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("STRIPE_PAYMENT_FAILED")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        log.info("Resource not found: type={}, id={}", ex.getResourceType(), ex.getResourceId());
        Map<String, Object> details = new HashMap<>();
        details.put("resource_type", ex.getResourceType());
        details.put("resource_id", ex.getResourceId());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("RESOURCE_NOT_FOUND")
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> ApiErrorResponse.FieldError.builder()
                        .field(fe.getField())
                        .message(fe.getDefaultMessage())
                        .rejectedValue(fe.getRejectedValue() != null ? fe.getRejectedValue().toString() : null)
                        .build())
                .collect(Collectors.toList());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("Request validation failed with " + fieldErrors.size() + " error(s)")
                .path(request.getRequestURI())
                .errorCode("VALIDATION_ERROR")
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            Exception ex, HttpServletRequest request) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("BAD_REQUEST")
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            OptimisticLockException ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Concurrent Modification")
                .message("Resource was modified by another request. Please retry.")
                .path(request.getRequestURI())
                .errorCode("OPTIMISTIC_LOCK_CONFLICT")
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.error("Data integrity violation: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Data Integrity Violation")
                .message("A database constraint was violated. Please check your input.")
                .path(request.getRequestURI())
                .errorCode("DATA_INTEGRITY_VIOLATION")
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ImageStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleImageStorage(
            ImageStorageException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.getReason()) {
            case "EMPTY_FILE", "CONTENT_TYPE_NOT_ALLOWED",
                 "EXTENSION_NOT_ALLOWED", "R2_NOT_CONFIGURED" -> HttpStatus.BAD_REQUEST;
            case "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "UPLOAD_FAILED", "DELETE_FAILED", "FILE_READ_ERROR" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> details = new HashMap<>();
        details.put("reason", ex.getReason());
        if (status == HttpStatus.PAYLOAD_TOO_LARGE) {
            details.put("max_mb", 20);
        }
        if ("R2_NOT_CONFIGURED".equals(ex.getReason())) {
            details.put("setup",
                    "Configure all R2_* environment variables from private.md before uploading images.");
        }
        log.warn("Image storage error: reason={} status={} message={}",
                ex.getReason(), status, ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(status.value())
                .error("Image Storage")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .errorCode("IMAGE_STORAGE_" + ex.getReason())
                .details(details)
                .build();
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        HttpStatusCode code = ex.getStatusCode();
        int status = code.value();
        if (status >= 400 && status < 500) {
            log.warn("ResponseStatus {} on path {}: {}", status, request.getRequestURI(), ex.getReason());
        } else {
            log.error("ResponseStatus {} on path {}: {}", status, request.getRequestURI(), ex.getReason(), ex);
        }
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(status)
                .error(HttpStatus.resolve(status) != null
                        ? HttpStatus.resolve(status).getReasonPhrase()
                        : "Status " + status)
                .message(ex.getReason() != null ? ex.getReason() : "Request failed")
                .path(request.getRequestURI())
                .errorCode("HTTP_" + status)
                .build();
        return ResponseEntity.status(code).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on path {}", request.getRequestURI(), ex);
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message(ex.getClass().getSimpleName() + ": " + (ex.getMessage() != null ? ex.getMessage() : "no detail"))
                .path(request.getRequestURI())
                .errorCode("INTERNAL_ERROR")
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorResponse> handleThrowable(
            Throwable t, HttpServletRequest request) {
        log.error("FATAL throwable on path {}: {}: {}",
                request.getRequestURI(), t.getClass().getName(),
                t.getMessage() != null ? t.getMessage() : "no message", t);
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Fatal Internal Error")
                .message(t.getClass().getSimpleName() + ": "
                        + (t.getMessage() != null ? t.getMessage() : "no detail"))
                .path(request.getRequestURI())
                .errorCode("FATAL_" + t.getClass().getSimpleName())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
