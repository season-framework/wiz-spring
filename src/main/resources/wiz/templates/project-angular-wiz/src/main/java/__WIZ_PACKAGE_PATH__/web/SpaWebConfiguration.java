package __WIZ_PACKAGE_ROOT__.web;

import java.util.Collections;
import java.util.List;

import __WIZ_PACKAGE_ROOT__.config.ApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

@Configuration(proxyBeanMethods = false)
public class SpaWebConfiguration implements WebMvcConfigurer {

    private final ApiProperties apiProperties;
    private final WebProperties webProperties;

    public SpaWebConfiguration(ApiProperties apiProperties, WebProperties webProperties) {
        this.apiProperties = apiProperties;
        this.webProperties = webProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(webProperties.getResources().getStaticLocations())
                .resourceChain(false)
                .addResolver(new SpaFallbackResourceResolver(apiProperties));
    }

    static final class SpaFallbackResourceResolver extends PathResourceResolver {

        private static final List<String> RESERVED_PATHS = List.of(
                "/app-config.json",
                "/v3/api-docs",
                "/swagger-ui",
                "/actuator");

        private final ApiProperties apiProperties;

        SpaFallbackResourceResolver(ApiProperties apiProperties) {
            this.apiProperties = apiProperties;
        }

        @Override
        protected Resource resolveResourceInternal(
                HttpServletRequest request,
                String requestPath,
                List<? extends Resource> locations,
                ResourceResolverChain chain) {
            Resource resource = super.resolveResourceInternal(request, requestPath, locations, chain);
            if (resource != null || !isHtmlNavigation(request, requestPath)) {
                return resource;
            }
            return super.resolveResourceInternal(request, "index.html", locations, chain);
        }

        private boolean isHtmlNavigation(HttpServletRequest request, String requestPath) {
            if (!HttpMethod.GET.matches(request.getMethod()) || !explicitlyAcceptsHtml(request)) {
                return false;
            }

            String fetchMode = request.getHeader("Sec-Fetch-Mode");
            if (fetchMode != null && !fetchMode.isBlank() && !fetchMode.equalsIgnoreCase("navigate")) {
                return false;
            }

            String path = normalizePath(requestPath);
            if (apiProperties.matchesApiPath(path) || RESERVED_PATHS.stream().anyMatch(root -> isAtOrBelow(path, root))) {
                return false;
            }

            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            return !lastSegment.contains(".");
        }

        private boolean explicitlyAcceptsHtml(HttpServletRequest request) {
            try {
                return MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)))
                        .stream()
                        .anyMatch(mediaType ->
                                (mediaType.getType().equalsIgnoreCase("text")
                                        && mediaType.getSubtype().equalsIgnoreCase("html"))
                                        || (mediaType.getType().equalsIgnoreCase("application")
                                        && mediaType.getSubtype().equalsIgnoreCase("xhtml+xml")));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        private String normalizePath(String requestPath) {
            String path = requestPath == null ? "" : requestPath.replace('\\', '/');
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return "/" + path;
        }

        private boolean isAtOrBelow(String path, String root) {
            return path.equals(root) || path.startsWith(root + "/");
        }
    }
}
