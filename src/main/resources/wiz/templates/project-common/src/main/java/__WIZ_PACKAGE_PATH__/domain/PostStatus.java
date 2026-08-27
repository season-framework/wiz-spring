package __WIZ_PACKAGE_ROOT__.domain;

import java.util.Locale;

public enum PostStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PostStatus from(String value, PostStatus defaultStatus) {
        if (value == null || value.isBlank()) {
            return defaultStatus;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status must be one of draft, published, archived");
        }
    }
}
