package __WIZ_PACKAGE_ROOT__.service;

import __WIZ_PACKAGE_ROOT__.api.model.AuthModels.SessionResponse;
import __WIZ_PACKAGE_ROOT__.domain.UserEntity;
import __WIZ_PACKAGE_ROOT__.domain.UserRole;
import __WIZ_PACKAGE_ROOT__.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SessionAuthService {

    private static final String USER_ID = "sample.user.id";
    private static final String USER_EMAIL = "sample.user.email";
    private static final String USER_NAME = "sample.user.name";
    private static final String USER_ROLE = "sample.user.role";

    private final UserRepository users;

    public SessionAuthService(UserRepository users) {
        this.users = users;
    }

    public SessionResponse login(HttpServletRequest request, UserEntity user) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        refreshSession(session, user);
        return response(user);
    }

    public SessionResponse current(HttpServletRequest request) {
        UserEntity user = currentUser(request.getSession(false));
        return user == null ? SessionResponse.anonymous() : response(user);
    }

    public AuthenticatedUser requireUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        UserEntity user = currentUser(session);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return authenticated(user);
    }

    public AuthenticatedUser requireAdmin(HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        if (user.role() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
        return user;
    }

    public void refreshProfile(HttpServletRequest request, UserEntity user) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        refreshSession(session, user);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private SessionResponse response(UserEntity user) {
        return new SessionResponse(
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
}
