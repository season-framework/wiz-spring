package __WIZ_PACKAGE_ROOT__.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import __WIZ_PACKAGE_ROOT__.api.model.MemberModels.InviteRequest;
import __WIZ_PACKAGE_ROOT__.api.model.MemberModels.MemberResponse;
import __WIZ_PACKAGE_ROOT__.api.model.ProfileModels.ChangePasswordRequest;
import __WIZ_PACKAGE_ROOT__.api.model.ProfileModels.UpdateProfileRequest;
import __WIZ_PACKAGE_ROOT__.domain.UserEntity;
import __WIZ_PACKAGE_ROOT__.domain.UserRole;
import __WIZ_PACKAGE_ROOT__.repository.UserRepository;
import __WIZ_PACKAGE_ROOT__.service.SessionAuthService.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SessionAuthService sessions;

    public UserService(UserRepository users, PasswordEncoder passwords, SessionAuthService sessions) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    public UserEntity authenticate(String email, String password) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!passwords.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return user;
    }

    public List<MemberResponse> list(HttpServletRequest request, String text, String role) {
        sessions.requireUser(request);
        String normalizedText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return users.findAllByOrderByCreatedAtAsc().stream()
                .filter(user -> normalizedRole.isBlank() || user.getRole().value().equals(normalizedRole))
                .filter(user -> normalizedText.isBlank()
                        || user.getEmail().toLowerCase(Locale.ROOT).contains(normalizedText)
                        || user.getName().toLowerCase(Locale.ROOT).contains(normalizedText))
                .map(this::response)
                .toList();
    }

    public MemberResponse get(HttpServletRequest request, String id) {
        sessions.requireUser(request);
        return response(find(id));
    }

    @Transactional
    public MemberResponse invite(HttpServletRequest request, InviteRequest input) {
        sessions.requireAdmin(request);
        String email = normalizeEmail(input.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 등록된 사용자입니다.");
        }
        String name = input.name() == null || input.name().isBlank()
                ? email.substring(0, email.indexOf('@'))
                : input.name().trim();
        UserRole role = parseRole(input.role(), UserRole.VIEWER);
        Instant now = Instant.now();
        UserEntity user = new UserEntity(
                UUID.randomUUID().toString(),
                email,
                passwords.encode("welcome1"),
                name,
                "",
                role,
                now);
        return response(users.save(user));
    }

    @Transactional
    public void remove(HttpServletRequest request, String id) {
        AuthenticatedUser actor = sessions.requireAdmin(request);
        if (actor.id().equals(id)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "현재 로그인한 계정은 삭제할 수 없습니다.");
        }
        users.delete(find(id));
    }

    public MemberResponse profile(HttpServletRequest request) {
        AuthenticatedUser current = sessions.requireUser(request);
        return response(find(current.id()));
    }

    @Transactional
    public MemberResponse updateProfile(HttpServletRequest request, UpdateProfileRequest input) {
        AuthenticatedUser current = sessions.requireUser(request);
        UserEntity user = find(current.id());
        user.updateProfile(
                input.name().trim(),
                input.mobile() == null ? "" : input.mobile().trim(),
                Instant.now());
        sessions.refreshProfile(request, user);
        return response(user);
    }

    @Transactional
    public void changePassword(HttpServletRequest request, ChangePasswordRequest input) {
        AuthenticatedUser current = sessions.requireUser(request);
        UserEntity user = find(current.id());
        if (!passwords.matches(input.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다.");
        }
        user.changePassword(passwords.encode(input.newPassword()), Instant.now());
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

    private MemberResponse response(UserEntity user) {
        return new MemberResponse(
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
}
