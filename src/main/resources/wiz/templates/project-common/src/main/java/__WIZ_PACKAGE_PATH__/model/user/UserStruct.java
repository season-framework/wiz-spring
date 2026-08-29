package __WIZ_PACKAGE_ROOT__.model.user;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import __WIZ_PACKAGE_ROOT__.exception.ApiException;
import __WIZ_PACKAGE_ROOT__.security.SessionContext;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.AuthenticatedUser;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.SessionView;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class UserStruct {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SessionContext session;

    public UserStruct(UserRepository users, PasswordEncoder passwords, SessionContext session) {
        this.users = users;
        this.passwords = passwords;
        this.session = session;
    }

    public SessionView session() {
        return session.current();
    }

    public SessionView login(String email, String password) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!passwords.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return session.login(user);
    }

    public void logout() {
        session.logout();
    }

    public List<View> list(String text, String role) {
        session.requireUser();
        String normalizedText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return users.findAllByOrderByCreatedAtAsc().stream()
                .filter(user -> normalizedRole.isBlank() || user.getRole().value().equals(normalizedRole))
                .filter(user -> normalizedText.isBlank()
                        || user.getEmail().toLowerCase(Locale.ROOT).contains(normalizedText)
                        || user.getName().toLowerCase(Locale.ROOT).contains(normalizedText))
                .map(this::view)
                .toList();
    }

    public View get(String id) {
        session.requireUser();
        return view(find(id));
    }

    @Transactional
    public View invite(String emailValue, String nameValue, String roleValue) {
        session.requireAdmin();
        String email = normalizeEmail(emailValue);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 사용자입니다.");
        }
        String name = nameValue == null || nameValue.isBlank()
                ? email.substring(0, email.indexOf('@'))
                : nameValue.trim();
        UserRole role = parseRole(roleValue, UserRole.VIEWER);
        Instant now = Instant.now();
        UserEntity user = new UserEntity(
                UUID.randomUUID().toString(),
                email,
                passwords.encode("welcome1"),
                name,
                "",
                role,
                now);
        return view(users.save(user));
    }

    @Transactional
    public void remove(String id) {
        AuthenticatedUser actor = session.requireAdmin();
        if (actor.id().equals(id)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "현재 로그인한 계정은 삭제할 수 없습니다.");
        }
        users.delete(find(id));
    }

    public View profile() {
        AuthenticatedUser current = session.requireUser();
        return view(find(current.id()));
    }

    @Transactional
    public View updateProfile(String name, String mobile) {
        AuthenticatedUser current = session.requireUser();
        UserEntity user = find(current.id());
        user.updateProfile(
                name.trim(),
                mobile == null ? "" : mobile.trim(),
                Instant.now());
        session.refreshProfile(user);
        return view(user);
    }

    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        AuthenticatedUser current = session.requireUser();
        UserEntity user = find(current.id());
        if (!passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다.");
        }
        user.changePassword(passwords.encode(newPassword), Instant.now());
    }

    private UserEntity find(String id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    private UserRole parseRole(String value, UserRole defaultRole) {
        try {
            return UserRole.from(value, defaultRole);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private View view(UserEntity user) {
        return new View(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getMobile(),
                user.getRole().value(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record View(
            String id,
            String email,
            String name,
            String mobile,
            String role,
            Instant createdAt,
            Instant updatedAt) {
    }
}
