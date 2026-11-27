package com.sharif899.prodbackend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharif899.prodbackend.service.RateLimiter;
import com.sharif899.prodbackend.web.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Rate limiting for the API, applied before any controller runs. The
 * client is identified by the {@code X-Api-Key} header when one is
 * sent, and by its address otherwise. A request over the limit gets
 * 429 with a {@code Retry-After} header and the same error envelope
 * as every other error, and never reaches a controller.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = clientKey(request);
        RateLimiter.Decision decision = rateLimiter.tryConsume(key);
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimiter.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiResponse<Void> body = ApiResponse.error(new ApiResponse.ErrorDetail(
                    "RATE_LIMITED",
                    "Too many requests. Retry after " + decision.retryAfterSeconds() + " seconds.",
                    Map.of()));
            objectMapper.writeValue(response.getWriter(), body);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey.trim();
        }
        return "addr:" + request.getRemoteAddr();
    }
}
