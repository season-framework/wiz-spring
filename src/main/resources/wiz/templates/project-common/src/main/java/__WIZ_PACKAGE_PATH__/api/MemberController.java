package __WIZ_PACKAGE_ROOT__.api;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.api.model.MemberModels.InviteRequest;
import __WIZ_PACKAGE_ROOT__.api.model.MemberModels.MemberResponse;
import __WIZ_PACKAGE_ROOT__.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@ApiController("/members")
public class MemberController {

    private final UserService users;

    public MemberController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<MemberResponse> list(
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "") String role,
            HttpServletRequest request) {
        return users.list(request, text, role);
    }

    @GetMapping("/{id}")
    public MemberResponse get(@PathVariable String id, HttpServletRequest request) {
        return users.get(request, id);
    }

    @PostMapping
    public ResponseEntity<MemberResponse> invite(
            @Valid @RequestBody InviteRequest input,
            HttpServletRequest request) {
        MemberResponse member = users.invite(request, input);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(member.id())
                .toUri()).body(member);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id, HttpServletRequest request) {
        users.remove(request, id);
        return ResponseEntity.noContent().build();
    }
}
