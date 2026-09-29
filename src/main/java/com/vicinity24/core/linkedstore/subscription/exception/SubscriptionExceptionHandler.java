package com.vicinity24.core.linkedstore.subscription.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class SubscriptionExceptionHandler {

    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @ExceptionHandler(OrderLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleOrderLimitExceeded(
            OrderLimitExceededException ex, HttpServletRequest request) {

        Map<String, Object> details = new HashMap<>();
        details.put("storeId", ex.getStoreId() != null ? ex.getStoreId().toString() : null);
        details.put("currentCount", ex.getCurrentCount());
        details.put("limit", ex.getLimit());
        details.put("planRecommended", "CUSTOM_PLAN");
        details.put("upgradeUrl", "/pricing");
        details.put("currentMonth", YearMonth.now().format(YEAR_MONTH_FMT));

        Map<String, Object> body = new HashMap<>();
        body.put("status", "error");
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("path", request.getRequestURI());
        body.put("details", details);

        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }
}
