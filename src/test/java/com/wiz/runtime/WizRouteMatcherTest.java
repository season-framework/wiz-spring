package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WizRouteMatcherTest {

    @Test
    void matchesFlaskStylePathSegments() {
        WizSegment segment = new WizRouteMatcher().match("/auth/<path:path>", "/auth/logout").orElseThrow();

        assertEquals("logout", segment.require("path"));
    }

    @Test
    void matchesNamedSegmentsAndIgnoresTrailingSlash() {
        WizSegment segment = new WizRouteMatcher().match("/posts/<id>/<tab>", "/posts/123/edit/").orElseThrow();

        assertEquals("123", segment.require("id"));
        assertEquals("edit", segment.require("tab"));
    }

    @Test
    void returnsEmptyWhenPatternDoesNotMatch() {
        assertTrue(new WizRouteMatcher().match("/auth/<path:path>", "/dashboard").isEmpty());
    }
}