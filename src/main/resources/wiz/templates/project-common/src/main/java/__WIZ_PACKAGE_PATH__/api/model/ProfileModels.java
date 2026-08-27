package __WIZ_PACKAGE_ROOT__.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ProfileModels {

    private ProfileModels() {
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 40) String mobile) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }
}
