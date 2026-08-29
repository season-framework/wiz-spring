package __WIZ_PACKAGE_ROOT__.config;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.api")
public record ApiProperties(String prefix, Versioning versioning) {

    private static final String DEFAULT_PREFIX = "/api";

    public ApiProperties {
        prefix = normalizePrefix(prefix);
        versioning = versioning == null ? new Versioning(null, null, null) : versioning;
    }

    public String mappingPrefix() {
        return versioning.pathEnabled() ? prefix + "/{version}" : prefix;
    }

    public String clientPrefix() {
        if (!versioning.pathEnabled() || versioning.defaultVersion() == null) {
            return prefix;
        }
        return prefix + "/" + versioning.defaultVersion();
    }

    public int versionPathSegmentIndex() {
        return (int) prefix.chars().filter(character -> character == '/').count();
    }

    public boolean matchesApiPath(String requestPath) {
        return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
    }

    private static String normalizePrefix(String value) {
        String candidate = value == null || value.isBlank() ? DEFAULT_PREFIX : value.trim();
        if (!candidate.startsWith("/") || candidate.endsWith("/") || candidate.contains("//")
                || candidate.contains("{") || candidate.contains("}") || candidate.contains("*")
                || !candidate.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*")) {
            throw new IllegalArgumentException("app.api.prefix must be an absolute path such as /api or /api/v2");
        }
        return candidate;
    }

    public record Versioning(String mode, String defaultVersion, List<String> supportedVersions) {

        public Versioning {
            mode = mode == null || mode.isBlank() ? "none" : mode.trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("none") && !mode.equals("path")) {
                throw new IllegalArgumentException("app.api.versioning.mode must be none or path");
            }
            defaultVersion = defaultVersion == null || defaultVersion.isBlank() ? null : defaultVersion.trim();
            if (defaultVersion != null && !defaultVersion.matches("[A-Za-z0-9._~-]+")) {
                throw new IllegalArgumentException("app.api.versioning.default-version must be one path segment");
            }
            if (mode.equals("path") && defaultVersion == null) {
                throw new IllegalArgumentException(
                        "app.api.versioning.default-version is required when mode is path");
            }
            supportedVersions = supportedVersions == null ? List.of() : List.copyOf(supportedVersions);
        }

        public boolean pathEnabled() {
            return mode.equals("path");
        }
    }
}
