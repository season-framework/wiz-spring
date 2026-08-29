package __WIZ_PACKAGE_ROOT__.security;

import __WIZ_PACKAGE_ROOT__.exception.ApiException;
import __WIZ_PACKAGE_ROOT__.model.user.UserEntity;
import __WIZ_PACKAGE_ROOT__.model.user.UserRepository;
import __WIZ_PACKAGE_ROOT__.model.user.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class SessionContext {

    private static final String USER_ID = "sample.user.id";
    private static final String USER_EMAIL = "sample.user.email";
    private static final String USER_NAME = "sample.user.name";
    private static final String USER_ROLE = "sample.user.role";

    private final HttpServletRequest request;
    private final UserRepository users;

    public SessionContext(HttpServletRequest request, UserRepository users) {
        this.request = request;
        this.users = users;
    }

    public SessionView login(UserEntity user) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        refreshSession(session, user);
        return response(user);
    }

    public SessionView current() {
        UserEntity user = currentUser(request.getSession(false));
        return user == null ? SessionView.anonymous() : response(user);
    }

    public AuthenticatedUser requireUser() {
        UserEntity user = currentUser(request.getSession(false));
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return authenticated(user);
    }

    public AuthenticatedUser requireAdmin() {
        AuthenticatedUser user = requireUser();
        if (user.role() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
        return user;
    }

    public void refreshProfile(UserEntity user) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            refreshSession(session, user);
        }
    }

    public void logout() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private SessionView response(UserEntity user) {
        return new SessionView(
                true,
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().value());
    }

    private AuthenticatedUser authenticated(UserEntity user) {
        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole());
    }

    private UserEntity currentUser(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object id = session.getAttribute(USER_ID);
        if (id == null || id.toString().isBlank()) {
            invalidate(session);
            return null;
        }
        UserEntity user = users.findById(id.toString()).orElse(null);
        if (user == null) {
            invalidate(session);
            return null;
        }
        refreshSession(session, user);
        return user;
    }

    private void refreshSession(HttpSession session, UserEntity user) {
        session.setAttribute(USER_ID, user.getId());
        session.setAttribute(USER_EMAIL, user.getEmail());
        session.setAttribute(USER_NAME, user.getName());
        session.setAttribute(USER_ROLE, user.getRole().value());
    }

    private void invalidate(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // The session was already invalidated by another request.
        }
    }

    public record AuthenticatedUser(
            String id,
            String email,
            String name,
            UserRole role) {
    }

    public record SessionView(
            boolean authenticated,
            String id,
            String email,
            String name,
            String role) {

        public static SessionView anonymous() {
            return new SessionView(false, null, null, null, null);
        }
    }
}
