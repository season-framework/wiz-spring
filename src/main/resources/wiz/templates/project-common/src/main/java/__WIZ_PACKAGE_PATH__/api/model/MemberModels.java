package __WIZ_PACKAGE_ROOT__.api.model;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MemberModels {

    private MemberModels() {
    }

    public record InviteRequest(
            @NotBlank @Email String email,
            @Size(max = 100) String name,
            @Size(max = 20) String role) {
    }

    public record MemberResponse(
            String id,
            String email,
            String name,
            String mobile,
            String role,
            Instant createdAt,
            Instant updatedAt) {
    }
}
