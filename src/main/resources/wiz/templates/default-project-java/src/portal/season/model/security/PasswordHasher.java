import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class PasswordHasher {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private PasswordHasher() {
    }

    public static String hash(String raw) {
        return ENCODER.encode(raw == null ? "" : raw);
    }

    public static boolean matches(String raw, String encoded) {
        return encoded != null && !encoded.isBlank() && ENCODER.matches(raw == null ? "" : raw, encoded);
    }
}
