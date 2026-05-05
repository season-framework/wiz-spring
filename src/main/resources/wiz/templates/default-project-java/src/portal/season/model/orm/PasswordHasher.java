import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class PasswordHasher {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return BCRYPT.encode(password);
    }

    public static boolean matches(String password, String hash) {
        return hash != null && BCRYPT.matches(password, hash);
    }
}