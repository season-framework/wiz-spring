package __WIZ_PACKAGE_ROOT__.web;

import __WIZ_PACKAGE_ROOT__.security.SessionContext;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.SessionView;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class HomeController {

    private final SessionContext session;

    public HomeController(SessionContext session) {
        this.session = session;
    }

    @GetMapping("/")
    public String home() {
        return session.current().authenticated() ? "redirect:/dashboard" : "redirect:/access";
    }

    @GetMapping("/access")
    public String access(Model model) {
        SessionView current = session.current();
        if (current.authenticated()) {
            return "redirect:/dashboard";
        }
        populate(model, "로그인", "access", current);
        return "access";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        return protectedView(model, "Dashboard", "dashboard", "dashboard");
    }

    @GetMapping("/members")
    public String members(Model model) {
        return protectedView(model, "멤버", "members", "members");
    }

    @GetMapping("/posts")
    public String posts(Model model) {
        return protectedView(model, "게시물", "posts", "posts");
    }

    @GetMapping("/posts/new")
    public String newPost(Model model) {
        return protectedView(model, "새 게시물", "posts", "post-editor");
    }

    @GetMapping("/posts/{id}")
    public String post(
            @PathVariable String id,
            Model model) {
        model.addAttribute("postId", id);
        return protectedView(model, "게시물 편집", "posts", "post-editor");
    }

    @GetMapping("/profile")
    public String profile(Model model) {
        return protectedView(model, "내 프로필", "profile", "profile");
    }

    @GetMapping("/chat")
    public String chat(Model model) {
        return protectedView(model, "실시간 채팅", "chat", "chat");
    }

    private String protectedView(
            Model model,
            String title,
            String navigation,
            String view) {
        SessionView current = session.current();
        if (!current.authenticated()) {
            return "redirect:/access";
        }
        populate(model, title, navigation, current);
        return view;
    }

    private void populate(Model model, String title, String navigation, SessionView session) {
        model.addAttribute("projectName", "__WIZ_PROJECT_NAME__");
        model.addAttribute("pageTitle", title);
        model.addAttribute("activeNavigation", navigation);
        model.addAttribute("sessionUser", session);
    }
}
