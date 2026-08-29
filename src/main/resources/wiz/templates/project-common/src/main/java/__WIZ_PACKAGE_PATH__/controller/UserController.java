package __WIZ_PACKAGE_ROOT__.controller;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.model.Struct;
import __WIZ_PACKAGE_ROOT__.model.user.UserStruct.View;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.SessionView;
import __WIZ_PACKAGE_ROOT__.web.ApiController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@ApiController
public class UserController {

    private final Struct struct;

    public UserController(Struct struct) {
        this.struct = struct;
    }

    @GetMapping("/auth/session")
    public SessionView session() {
        return struct.user().session();
    }

    @PostMapping("/auth/login")
    public SessionView login(@Valid @RequestBody LoginRequest input) {
        return struct.user().login(input.email(), input.password());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        struct.user().logout();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members")
    public List<View> list(
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "") String role) {
        return struct.user().list(text, role);
    }

    @GetMapping("/members/{id}")
    public View get(@PathVariable String id) {
        return struct.user().get(id);
    }

    @PostMapping("/members")
    public ResponseEntity<View> invite(@Valid @RequestBody InviteRequest input) {
        View member = struct.user().invite(input.email(), input.name(), input.role());
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(member.id())
                .toUri()).body(member);
    }

    @DeleteMapping("/members/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        struct.user().remove(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profile")
    public View profile() {
        return struct.user().profile();
    }

    @PutMapping("/profile")
    public View updateProfile(@Valid @RequestBody UpdateProfileRequest input) {
        return struct.user().updateProfile(input.name(), input.mobile());
    }

    @PutMapping("/profile/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest input) {
        struct.user().changePassword(input.currentPassword(), input.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record InviteRequest(
            @NotBlank @Email String email,
            @Size(max = 100) String name,
            @Size(max = 20) String role) {
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
