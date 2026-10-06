package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.config.BrandProperties;
import com.vicinity24.core.linkedstore.api.dto.ApiErrorResponse;
import com.vicinity24.core.linkedstore.api.dto.MailContactRequest;
import com.vicinity24.core.linkedstore.api.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/contact-sales")
@RequiredArgsConstructor
public class ContactSalesController {

    private static final int RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final int RATE_LIMIT_MAX_REQUESTS = 5;
    private static final int RATE_LIMIT_GLOBAL_MAX_PER_MINUTE = 50;

    private final EmailService emailService;
    private final BrandProperties brandProperties;

    private final Map<String, Deque<Instant>> clientWindows = new ConcurrentHashMap<>();
    private final Deque<Instant> globalWindow = new ArrayDeque<>();

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> submitContactSales(
            @Valid @RequestBody MailContactRequest body,
            HttpServletRequest request) {

        String clientIp = resolveClientIp(request);
        Instant now = Instant.now();

        if (!isWithinGlobalRate(now)) {
            log.warn("Contact-sales global rate limit exceeded. clientIp={}", clientIp);
            return buildRateLimitResponse("GLOBAL_RATE_LIMIT", "Too many submissions right now. Please try again in a minute.");
        }
        if (!isWithinClientRate(clientIp, now)) {
            log.warn("Contact-sales client rate limit exceeded. clientIp={}", clientIp);
            return buildRateLimitResponse("RATE_LIMITED", "Too many submissions from this address. Please try again in a minute.");
        }

        String destinationEmail = resolveDestinationEmail();
        boolean sent;
        try {
            sent = emailService.sendContactSales(body, destinationEmail);
        } catch (MailException me) {
            log.error("Contact-sales SMTP send failed to={} senderEmail={}", destinationEmail, body.getEmail(), me);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiErrorResponse.builder()
                    .timestamp(OffsetDateTime.now())
                    .status(HttpStatus.BAD_GATEWAY.value())
                    .error("Mail Gateway Error")
                    .message("We couldn't deliver your message right now. Please try again in a minute or email us directly.")
                    .errorCode("SMTP_SEND_FAILED")
                    .build());
        } catch (RuntimeException re) {
            log.error("Contact-sales unexpected error for senderEmail={}", body.getEmail(), re);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.builder()
                    .timestamp(OffsetDateTime.now())
                    .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                    .error("Unexpected Server Error")
                    .message("Something went wrong on our side. Please try again or email info@vicinity24.com directly.")
                    .errorCode("UNEXPECTED_ERROR")
                    .build());
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", sent ? "sent" : "queued");
        resp.put("destination", maskAddress(destinationEmail));
        resp.put("message", sent
                ? "Thanks — your message was sent. Our team will reply to the email you provided."
                : "Thanks — we received your message and will be in touch shortly.");
        return ResponseEntity.status(sent ? HttpStatus.OK : HttpStatus.ACCEPTED).body(resp);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(ApiErrorResponse.FieldError.builder()
                    .field(fe.getField())
                    .message(fe.getDefaultMessage())
                    .rejectedValue(fe.getRejectedValue() == null ? null : String.valueOf(fe.getRejectedValue()))
                    .build());
        }
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("Please review the form and try again.")
                .errorCode("VALIDATION_FAILED")
                .path(req.getRequestURI())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    private String resolveDestinationEmail() {
        String configured = brandProperties != null ? brandProperties.getContactSalesTo() : null;
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "info@vicinity24.com";
    }

    private static String maskAddress(String email) {
        if (email == null || !email.contains("@")) return email;
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        String maskedLocal;
        if (local.length() <= 2) maskedLocal = local.charAt(0) + "*".repeat(Math.max(1, local.length()));
        else maskedLocal = local.charAt(0) + "*".repeat(Math.max(3, local.length() - 2)) + local.charAt(local.length() - 1);
        return maskedLocal + "@" + domain;
    }

    private ResponseEntity<ApiErrorResponse> buildRateLimitResponse(String code, String message) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .error("Too Many Requests")
                .message(message)
                .errorCode(code)
                .build();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    private synchronized boolean isWithinGlobalRate(Instant now) {
        Instant cutoff = now.minusSeconds(RATE_LIMIT_WINDOW_SECONDS);
        while (!globalWindow.isEmpty() && globalWindow.peekFirst().isBefore(cutoff)) {
            globalWindow.pollFirst();
        }
        if (globalWindow.size() >= RATE_LIMIT_GLOBAL_MAX_PER_MINUTE) return false;
        globalWindow.addLast(now);
        return true;
    }

    private boolean isWithinClientRate(String clientIp, Instant now) {
        Instant cutoff = now.minusSeconds(RATE_LIMIT_WINDOW_SECONDS);
        Deque<Instant> deque = clientWindows.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= RATE_LIMIT_MAX_REQUESTS) return false;
            deque.addLast(now);
            return true;
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header != null && !header.isBlank()) {
            int comma = header.indexOf(',');
            return (comma > 0 ? header.substring(0, comma) : header).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }
}
