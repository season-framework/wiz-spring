package com.wiz.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import com.wiz.runtime.WizRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

class WizHttpControllerTest {

    @Test
    void buildsWizRequestFromQueryStringAndFormBodyOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/search");
        request.setQueryString("text=query-value");
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE + "; charset=UTF-8");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent("text=form-value&filter=active".getBytes(StandardCharsets.UTF_8));

        WizRequest wizRequest = WizHttpController.toWizRequest(request);

        assertEquals("query-value", wizRequest.query("text").orElseThrow());
        assertEquals("active", wizRequest.query("filter").orElseThrow());
        assertEquals(request.getSession(false), wizRequest.httpSession());
    }

    @Test
    void mergesJsonBodyIntoQueryFacade() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/search");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent("{\"text\":\"json-value\"}".getBytes(StandardCharsets.UTF_8));

        WizRequest wizRequest = WizHttpController.toWizRequest(request);

        assertEquals("json-value", wizRequest.query("text", "hello"));
        assertEquals("json-value", wizRequest.json("text").orElseThrow());
        assertEquals("{\"text\":\"json-value\"}", wizRequest.body());
    }

}
