package com.wiz.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.wiz.config.WizApiProperties;
import com.wiz.config.WizHttpProperties;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WizHttpController {

    private final StaticFileService staticFiles;
    private final AppApiDispatcher appApiDispatcher;
    private final RouteDispatcher routeDispatcher;
    private final WizHttpProperties httpProperties;
    private final WizApiProperties apiProperties;

    @Autowired
    public WizHttpController(StaticFileService staticFiles, AppApiDispatcher appApiDispatcher, RouteDispatcher routeDispatcher, WizHttpProperties httpProperties, WizApiProperties apiProperties) {
        this.staticFiles = staticFiles;
        this.appApiDispatcher = appApiDispatcher;
        this.routeDispatcher = routeDispatcher;
        this.httpProperties = httpProperties;
        this.apiProperties = apiProperties;
    }

    public WizHttpController(StaticFileService staticFiles, AppApiDispatcher appApiDispatcher, RouteDispatcher routeDispatcher, WizHttpProperties httpProperties) {
        this(staticFiles, appApiDispatcher, routeDispatcher, httpProperties, new WizApiProperties());
    }

    public WizHttpController(StaticFileService staticFiles, AppApiDispatcher appApiDispatcher, RouteDispatcher routeDispatcher) {
        this(staticFiles, appApiDispatcher, routeDispatcher, new WizHttpProperties());
    }

    public WizHttpController(StaticFileService staticFiles, AppApiDispatcher appApiDispatcher) {
        this(staticFiles, appApiDispatcher, null);
    }

    @RequestMapping(path = "/wiz/config.js", method = RequestMethod.GET)
    public ResponseEntity<String> config() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/javascript; charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body("window.__WIZ_CONFIG__ = Object.assign(window.__WIZ_CONFIG__ || {}, { apiPrefix: \""
                        + escapeJavaScript(apiProperties.getPrefix())
                        + "\" });\n");
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    ResponseEntity<ResponseEnvelope> requestBodyTooLargeResponse(RequestBodyTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ResponseEnvelope(HttpStatus.PAYLOAD_TOO_LARGE.value(), Map.of("error", "request body too large")));
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
        ApiRoute apiRoute = apiRoute(request);
        if (apiRoute.matched()) {
            if (!apiRoute.valid()) {
                return ResponseEntity.notFound().build();
            }
            WizRequest wizRequest;
            try {
                wizRequest = toWizRequest(request, httpProperties);
            } catch (IllegalArgumentException exception) {
                return badRequestResponse(exception);
            }
            WizResult result = appApiDispatcher.dispatch(wizRequest, apiRoute.appId(), apiRoute.function(), apiRoute.extraPath());
            return resultResponse(result);
        }
        if (routeDispatcher != null) {
            WizRequest wizRequest;
            try {
                wizRequest = toWizRequest(request, httpProperties);
            } catch (IllegalArgumentException exception) {
                return badRequestResponse(exception);
            }
            java.util.Optional<WizResult> routeResult = routeDispatcher.dispatch(wizRequest);
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

    private ResponseEntity<ResponseEnvelope> badRequestResponse(IllegalArgumentException exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank() ? "bad request" : exception.getMessage();
        return ResponseEntity.badRequest().body(new ResponseEnvelope(HttpStatus.BAD_REQUEST.value(), Map.of("error", message)));
    }

    private ApiRoute apiRoute(HttpServletRequest request) {
        String path = lookupPath(request);
        String prefix = apiProperties.getPrefix();
        if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
            return ApiRoute.notMatched();
        }
        String relative = path.substring(prefix.length());
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        String[] parts = relative.split("/", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return ApiRoute.invalid();
        }
        String extraPath = parts.length == 3 ? parts[2] : "";
        return ApiRoute.matched(parts[0], parts[1], extraPath);
    }

    private String lookupPath(HttpServletRequest request) {
        String uri = Optional.ofNullable(request.getRequestURI()).orElse("");
        String contextPath = Optional.ofNullable(request.getContextPath()).orElse("");
        if (!contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
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
        return toWizRequest(request, new WizHttpProperties());
    }

    static WizRequest toWizRequest(HttpServletRequest request, WizHttpProperties httpProperties) throws IOException {
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
        if (isMultipart(request)) {
            return builder.build();
        }
        if (isFormUrlEncoded(request)) {
            builder.formUrlEncoded(requestBody(request, httpProperties));
        } else if (hasRequestBody(request)) {
            String body = requestBody(request, httpProperties);
            builder.body(body);
            if (isJson(request)) {
                builder.jsonBody(body);
            }
        }
        return builder.build();
    }

    private static boolean isFormUrlEncoded(HttpServletRequest request) {
        return MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(mediaType(request));
    }

    private static boolean isJson(HttpServletRequest request) {
        return MediaType.APPLICATION_JSON_VALUE.equals(mediaType(request));
    }

    private static boolean isMultipart(HttpServletRequest request) {
        return mediaType(request).startsWith("multipart/");
    }

    private static String mediaType(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasRequestBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) {
            return true;
        }
        if (request.getContentLengthLong() == 0) {
            return false;
        }
        return request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null || isJson(request);
    }

    private static String requestBody(HttpServletRequest request, WizHttpProperties httpProperties) throws IOException {
        long maxBodyBytes = httpProperties == null ? 0 : httpProperties.getMaxRequestBodyBytes();
        long contentLength = request.getContentLengthLong();
        if (maxBodyBytes > 0 && contentLength > maxBodyBytes) {
            throw requestBodyTooLarge();
        }
        String encoding = request.getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        byte[] body = readBody(request.getInputStream(), maxBodyBytes);
        return new String(body, charset);
    }

    private static byte[] readBody(InputStream input, long maxBodyBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = input.read(buffer)) != -1) {
            if (maxBodyBytes > 0 && read > maxBodyBytes - total) {
                throw requestBodyTooLarge();
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static String escapeJavaScript(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static RequestBodyTooLargeException requestBodyTooLarge() {
        return new RequestBodyTooLargeException();
    }

    private static Map<String, String> cookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Map.of();
        }
        return Arrays.stream(cookies).collect(Collectors.toMap(Cookie::getName, Cookie::getValue, (first, second) -> second));
    }

    private record ApiRoute(boolean matched, String appId, String function, String extraPath) {

        static ApiRoute notMatched() {
            return new ApiRoute(false, "", "", "");
        }

        static ApiRoute invalid() {
            return new ApiRoute(true, "", "", "");
        }

        static ApiRoute matched(String appId, String function, String extraPath) {
            return new ApiRoute(true, appId, function, extraPath);
        }

        boolean valid() {
            return !appId.isBlank() && !function.isBlank();
        }
    }

    static final class RequestBodyTooLargeException extends ResponseStatusException {

        RequestBodyTooLargeException() {
            super(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds wiz.http.max-request-body-bytes");
        }
    }
}
