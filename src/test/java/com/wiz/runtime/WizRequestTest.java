package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WizRequestTest {

    @Test
    void readsQueryStringAndFormUrlEncodedValues() {
        WizRequest request = WizRequest.builder()
                .queryString("text=query+value&encoded=a%2Fb")
                .formUrlEncoded("form=body+value")
                .build();

        assertEquals("query value", request.query("text").orElseThrow());
        assertEquals("a/b", request.query("encoded").orElseThrow());
        assertEquals("body value", request.query("form").orElseThrow());
    }

    @Test
    void queryStringValueTakesPrecedenceOverFormValue() {
        WizRequest request = WizRequest.builder()
                .queryString("text=query-value")
                .formUrlEncoded("text=form-value")
                .build();

        assertEquals("query-value", request.query("text").orElseThrow());
    }

    @Test
    void queryRequiredThrowsBadRequestWhenMissing() {
        WizBadRequestException exception = assertThrows(WizBadRequestException.class,
                () -> WizRequest.builder().build().queryRequired("value"));

        assertEquals("Missing required query value: value", exception.getMessage());
        assertEquals(Map.of("error", "missing required query value", "name", "value"), exception.data());
    }

    @Test
    void readsHeadersAndCookies() {
        WizRequest request = WizRequest.builder()
                .header("X-WIZ", "yes")
                .cookie("sample-cookie", "main")
                .build();

        assertEquals("yes", request.header("x-wiz").orElseThrow());
        assertEquals("main", request.cookie("sample-cookie").orElseThrow());
    }

    @Test
    void exposesJsonBodyAndMergesTopLevelValuesIntoQueryFacade() {
        WizRequest request = WizRequest.builder()
                .jsonBody("{\"name\":\"User One\",\"count\":3,\"payload\":{\"id\":\"p1\"}}")
                .build();

        assertEquals("User One", request.json("name").orElseThrow());
        assertEquals(3, request.json("count").orElseThrow());
        assertEquals("User One", request.query("name", "fallback"));
        assertEquals("3", request.query("count", "0"));
        assertEquals("{\"id\":\"p1\"}", request.query("payload", "{}"));
    }

    @Test
    void rejectsInvalidJsonBody() {
        assertThrows(IllegalArgumentException.class, () -> WizRequest.builder().jsonBody("not-json"));
    }
}
