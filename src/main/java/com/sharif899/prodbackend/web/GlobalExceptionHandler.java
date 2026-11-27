package com.sharif899.prodbackend.web;

import com.sharif899.prodbackend.exception.DuplicateOrderException;
import com.sharif899.prodbackend.exception.DuplicatePaymentException;
import com.sharif899.prodbackend.exception.IdempotencyConflictException;
import com.sharif899.prodbackend.exception.InvalidOrderStateException;
import com.sharif899.prodbackend.exception.OrderNotFoundException;
import com.sharif899.prodbackend.exception.PaymentAmountMismatchException;
import com.sharif899.prodbackend.web.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every error leaves this service in the same envelope as a success
 * ({@code success: false, error: {code, message, fieldErrors}}), so
 * clients only ever have to handle one shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() == null
                                ? "Invalid value" : fieldError.getDefaultMessage(),
                        (first, second) -> first));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage() == null
                                ? "Invalid value" : violation.getMessage(),
                        (first, second) -> first));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedJson(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                "Request body is not valid JSON", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'", Map.of());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(OrderNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateOrder(DuplicateOrderException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.put("clientReference", "already exists");
        if (ex.getExistingOrderId() != null) {
            fieldErrors.put("existingOrderId", ex.getExistingOrderId().toString());
        }
        return error(HttpStatus.CONFLICT, "DUPLICATE_ORDER", ex.getMessage(), fieldErrors);
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicatePayment(DuplicatePaymentException ex) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_PAYMENT", ex.getMessage(),
                Map.of("reference", ex.getReference()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_CONFLICT", ex.getMessage(),
                Map.of("idempotencyKey", ex.getIdempotencyKey()));
    }

    @ExceptionHandler({InvalidOrderStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidState(InvalidOrderStateException ex) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), Map.of());
    }

    @ExceptionHandler(PaymentAmountMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleAmountMismatch(PaymentAmountMismatchException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_AMOUNT_MISMATCH", ex.getMessage(),
                Map.of("expectedAmountMinor", String.valueOf(ex.getExpectedMinor()),
                        "actualAmountMinor", String.valueOf(ex.getActualMinor())));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "The order was modified by another request; retry", Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Last resort: a unique constraint fired somewhere the service
        // did not already convert to a business error. Never a 500.
        log.warn("Data integrity violation surfaced as a conflict", ex);
        return error(HttpStatus.CONFLICT, "CONFLICT",
                "A record with the same unique value already exists", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", Map.of());
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String code,
                                                    String message, Map<String, String> fieldErrors) {
        return ResponseEntity.status(status)
                .body(ApiResponse.error(new ApiResponse.ErrorDetail(code, message, fieldErrors)));
    }
}
