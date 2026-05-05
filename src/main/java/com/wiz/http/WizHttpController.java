package com.wiz.http;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.wiz.dispatch.AppApiDispatcher;
import com.wiz.dispatch.RouteDispatcher;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WizHttpController {

    private final StaticFileService staticFiles;
    private final AppApiDispatcher appApiDispatcher;
    private final RouteDispatcher routeDispatcher;

    @Autowired
    public WizHttpController(StaticFileService staticFiles, AppApiDispatcher appApiDispatcher, RouteDispatcher routeDispatcher) {
        this.staticFiles = staticFiles;
        this.appApiDispatcher = appApiDispatcher;
        this.routeDispatcher = routeDispatcher;
    }

    public WizHttpController(StaticFileService staticFiles, AppApiDispatcher appApiDispatcher) {
        this(staticFiles, appApiDispatcher, null);
    }

    @RequestMapping(path = { "/wiz/api/{appId}/{function}", "/wiz/api/{appId}/{function}/**" }, method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity<Object> appApi(
            @PathVariable String appId,
            @PathVariable String function,
            HttpServletRequest request) throws IOException {
        WizResult result = appApiDispatcher.dispatch(toWizRequest(request), appId, function, "");
        return resultResponse(result);
    }

    @RequestMapping(path = "/assets/**", method = RequestMethod.GET)
    public ResponseEntity<Resource> asset(HttpServletRequest request) {
        String assetPath = request.getRequestURI().substring("/assets/".length());
        return staticFiles.findAsset(assetPath, cookies(request))
                .map(this::staticResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @RequestMapping(path = "/**", method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity<?> spa(HttpServletRequest request) throws IOException {
        if (routeDispatcher != null) {
            java.util.Optional<WizResult> routeResult = routeDispatcher.dispatch(toWizRequest(request));
            if (routeResult.isPresent()) {
                return resultResponse(routeResult.get());
            }
        }
        if (!RequestMethod.GET.name().equals(request.getMethod())) {
            return ResponseEntity.notFound().build();
        }
        return staticFiles.findSpaFile(request.getRequestURI(), cookies(request))
                .map(this::staticResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<Object> resultResponse(WizResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        result.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        if (result.entity() == null) {
            return builder.build();
        }
        return builder.body(result.entity());
    }

    private ResponseEntity<Resource> staticResponse(StaticFileService.StaticFile file) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.mediaType()))
                    .header(HttpHeaders.CACHE_CONTROL, file.cacheControl())
                    .contentLength(java.nio.file.Files.size(file.path()))
                    .body(new FileSystemResource(file.path()));
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().build();
        }
    }

    static WizRequest toWizRequest(HttpServletRequest request) throws IOException {
        WizRequest.Builder builder = WizRequest.builder()
                .method(request.getMethod())
                .path(request.getRequestURI())
                .queryString(request.getQueryString())
                .remoteAddress(request.getRemoteAddr())
                .session(request.getSession(true));
        cookies(request).forEach(builder::cookie);
        if (request.getHeaderNames() != null) {
            Collections.list(request.getHeaderNames())
                    .forEach(name -> Collections.list(request.getHeaders(name))
                            .forEach(value -> builder.header(name, value)));
        }
        if (isFormUrlEncoded(request)) {
            builder.formUrlEncoded(requestBody(request));
        } else if (hasRequestBody(request)) {
            String body = requestBody(request);
            builder.body(body);
            if (isJson(request)) {
                builder.jsonBody(body);
            }
        }
        return builder.build();
    }

    private static boolean isFormUrlEncoded(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(mediaType);
    }

    private static boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return MediaType.APPLICATION_JSON_VALUE.equals(mediaType);
    }

    private static boolean hasRequestBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0;
    }

    private static String requestBody(HttpServletRequest request) throws IOException {
        String encoding = request.getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new String(request.getInputStream().readAllBytes(), charset);
    }

    private static Map<String, String> cookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Map.of();
        }
        return Arrays.stream(cookies).collect(Collectors.toMap(Cookie::getName, Cookie::getValue, (first, second) -> second));
    }
}
