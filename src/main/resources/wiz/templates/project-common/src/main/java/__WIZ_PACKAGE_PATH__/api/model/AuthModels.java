package __WIZ_PACKAGE_ROOT__.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public final class AuthModels {

    private AuthModels() {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record SessionResponse(
            boolean authenticated,
            String id,
            String email,
            String name,
            String role) {

        public static SessionResponse anonymous() {
            return new SessionResponse(false, null, null, null, null);
        }
    }
}
