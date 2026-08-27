package __WIZ_PACKAGE_ROOT__.api;

import __WIZ_PACKAGE_ROOT__.api.model.AuthModels.LoginRequest;
import __WIZ_PACKAGE_ROOT__.api.model.AuthModels.SessionResponse;
import __WIZ_PACKAGE_ROOT__.service.SessionAuthService;
import __WIZ_PACKAGE_ROOT__.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@ApiController("/auth")
public class AuthController {

    private final UserService users;
    private final SessionAuthService sessions;

    public AuthController(UserService users, SessionAuthService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest request) {
        return sessions.current(request);
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest input, HttpServletRequest request) {
        return sessions.login(request, users.authenticate(input.email(), input.password()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(request);
        return ResponseEntity.noContent().build();
    }
}
