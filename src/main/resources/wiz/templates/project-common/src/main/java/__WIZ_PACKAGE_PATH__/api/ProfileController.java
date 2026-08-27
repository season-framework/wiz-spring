package __WIZ_PACKAGE_ROOT__.api;

import __WIZ_PACKAGE_ROOT__.api.model.MemberModels.MemberResponse;
import __WIZ_PACKAGE_ROOT__.api.model.ProfileModels.ChangePasswordRequest;
import __WIZ_PACKAGE_ROOT__.api.model.ProfileModels.UpdateProfileRequest;
import __WIZ_PACKAGE_ROOT__.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@ApiController("/profile")
public class ProfileController {

    private final UserService users;

    public ProfileController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public MemberResponse get(HttpServletRequest request) {
        return users.profile(request);
    }

    @PutMapping
    public MemberResponse update(
            @Valid @RequestBody UpdateProfileRequest input,
            HttpServletRequest request) {
        return users.updateProfile(request, input);
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest input,
            HttpServletRequest request) {
        users.changePassword(request, input);
        return ResponseEntity.noContent().build();
    }
}
