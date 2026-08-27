package __WIZ_PACKAGE_ROOT__.domain;

import java.util.Locale;

public enum UserRole {
    ADMIN,
    USER,
    EDITOR,
    VIEWER;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static UserRole from(String value, UserRole defaultRole) {
        if (value == null || value.isBlank()) {
            return defaultRole;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("role must be one of admin, user, editor, viewer");
        }
    }
}
