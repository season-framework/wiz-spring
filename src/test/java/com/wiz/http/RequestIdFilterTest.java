package com.wiz.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    @Test
    void preservesSafeRequestIdAndClearsMdc() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/smoke");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcValue = new AtomicReference<>();
        request.addHeader(RequestIdFilter.HEADER_NAME, "req-123");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            mdcValue.set(MDC.get(RequestIdFilter.MDC_KEY));
        });

        assertEquals("req-123", response.getHeader(RequestIdFilter.HEADER_NAME));
        assertEquals("req-123", mdcValue.get());
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void generatesRequestIdWhenHeaderIsUnsafe() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/smoke");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestIdFilter.HEADER_NAME, "");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertNotNull(response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNotEquals("", response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }
}