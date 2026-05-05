package com.wiz.http;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = requestId(request.getHeader(HEADER_NAME));
        response.setHeader(HEADER_NAME, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String requestId(String provided) {
        if (isSafeRequestId(provided)) {
            return provided;
        }
        return UUID.randomUUID().toString();
    }

    private boolean isSafeRequestId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return false;
        }
        return value.chars().allMatch(character -> character > 31 && character < 127);
    }
}