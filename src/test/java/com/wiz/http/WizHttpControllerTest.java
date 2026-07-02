package com.wiz.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.wiz.config.WizApiProperties;
import com.wiz.config.WizHttpProperties;
import com.wiz.dispatch.AppApiDispatcher;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRuntime;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

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

    @Test
    void rejectsRequestBodyOverConfiguredLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/search");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"text\":\"json-value\"}".getBytes(StandardCharsets.UTF_8));
        WizHttpProperties properties = new WizHttpProperties();
        properties.setMaxRequestBodyBytes(4);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> WizHttpController.toWizRequest(request, properties));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatusCode());
    }

    @Test
    void keepsRequestBodyUnlimitedByDefault() throws Exception {
        String text = "x".repeat(128);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/search");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(("{\"text\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8));

        WizRequest wizRequest = WizHttpController.toWizRequest(request, new WizHttpProperties());

        assertEquals(text, wizRequest.query("text").orElseThrow());
    }

    @Test
    void allowsRequestBodyWithinConfiguredLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/search");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        WizHttpProperties properties = new WizHttpProperties();
        properties.setMaxRequestBodyBytes(11);

        WizRequest wizRequest = WizHttpController.toWizRequest(request, properties);

        assertEquals(true, wizRequest.json("ok").orElseThrow());
    }

    @Test
    void rejectsChunkedRequestBodyOverConfiguredLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/search") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        request.setContent("{\"text\":\"json-value\"}".getBytes(StandardCharsets.UTF_8));
        WizHttpProperties properties = new WizHttpProperties();
        properties.setMaxRequestBodyBytes(4);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> WizHttpController.toWizRequest(request, properties));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatusCode());
    }

    @Test
    void skipsMultipartRequestBodyLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/upload");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=test");
        request.setContent("x".repeat(128).getBytes(StandardCharsets.UTF_8));
        WizHttpProperties properties = new WizHttpProperties();
        properties.setMaxRequestBodyBytes(4);

        WizRequest wizRequest = WizHttpController.toWizRequest(request, properties);

        assertEquals("", wizRequest.body());
    }

    @Test
    void requestBodyLimitHandlerReturnsPayloadTooLargeEnvelope() {
        WizHttpController controller = new WizHttpController(null, null);

        ResponseEntity<ResponseEnvelope> response = controller.requestBodyTooLargeResponse(new WizHttpController.RequestBodyTooLargeException());

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(413, response.getBody().code());
        assertEquals(Map.of("error", "request body too large"), response.getBody().data());
    }

    @Test
    void dispatchesAppApiThroughConfiguredPrefix() throws Exception {
        WizApiProperties apiProperties = new WizApiProperties();
        apiProperties.setPrefix("/custom/api/");
        CapturingAppApiDispatcher dispatcher = new CapturingAppApiDispatcher();
        WizHttpController controller = new WizHttpController(null, dispatcher, null, new WizHttpProperties(), apiProperties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/custom/api/page.dashboard/overview/trailing");

        ResponseEntity<?> response = controller.spa(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("page.dashboard", dispatcher.appId);
        assertEquals("overview", dispatcher.function);
        assertEquals("trailing", dispatcher.path);
        assertEquals("/custom/api/page.dashboard/overview/trailing", dispatcher.request.path());
    }

    @Test
    void appApiInvalidJsonReturnsBadRequestEnvelope() throws Exception {
        CapturingAppApiDispatcher dispatcher = new CapturingAppApiDispatcher();
        WizHttpController controller = new WizHttpController(null, dispatcher, null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wiz/api/page.dashboard/overview");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("1".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response = controller.spa(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody() instanceof ResponseEnvelope);
        ResponseEnvelope body = (ResponseEnvelope) response.getBody();
        assertEquals(400, body.code());
        assertEquals(Map.of("error", "Invalid JSON request body"), body.data());
        assertEquals(null, dispatcher.appId);
    }

    @Test
    void configJsExposesConfiguredApiPrefix() {
        WizApiProperties apiProperties = new WizApiProperties();
        apiProperties.setPrefix("/custom/api/");
        WizHttpController controller = new WizHttpController(null, new CapturingAppApiDispatcher(), null, new WizHttpProperties(), apiProperties);

        ResponseEntity<String> response = controller.config();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getBody().contains("apiPrefix: \"/custom/api\""));
    }

    @Test
    void apiPrefixDefaultsBlankAndTrimsTrailingSlash() {
        WizApiProperties properties = new WizApiProperties();
        properties.setPrefix(" /api/v1/ ");
        assertEquals("/api/v1", properties.getPrefix());

        properties.setPrefix(" ");
        assertEquals(WizApiProperties.DEFAULT_PREFIX, properties.getPrefix());
    }

    @Test
    void apiPrefixRejectsUnsafePathPatternCharacters() {
        WizApiProperties properties = new WizApiProperties();
        properties.setPrefix("/api/{app}");

        var violations = Validation.buildDefaultValidatorFactory().getValidator().validate(properties);

        assertFalse(violations.isEmpty());
    }

    private static final class CapturingAppApiDispatcher extends AppApiDispatcher {
        private String appId;
        private String function;
        private String path;
        private WizRequest request;

        private CapturingAppApiDispatcher() {
            super((WizRuntime) null);
        }

        @Override
        public WizResult dispatch(WizRequest request, String appId, String function, String path) {
            this.request = request;
            this.appId = appId;
            this.function = function;
            this.path = path;
            return WizResult.envelope(200, Map.of("ok", true));
        }
    }

}
