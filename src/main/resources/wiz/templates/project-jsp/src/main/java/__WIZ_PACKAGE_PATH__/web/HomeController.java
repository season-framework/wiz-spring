package __WIZ_PACKAGE_ROOT__.web;

import __WIZ_PACKAGE_ROOT__.api.model.AuthModels.SessionResponse;
import __WIZ_PACKAGE_ROOT__.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class HomeController {

    private final SessionAuthService sessions;

    public HomeController(SessionAuthService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/")
    public String home(HttpServletRequest request) {
        return sessions.current(request).authenticated() ? "redirect:/dashboard" : "redirect:/access";
    }

    @GetMapping("/access")
    public String access(HttpServletRequest request, Model model) {
        SessionResponse session = sessions.current(request);
        if (session.authenticated()) {
            return "redirect:/dashboard";
        }
        populate(model, "로그인", "access", session);
        return "access";
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        return protectedView(request, model, "Dashboard", "dashboard", "dashboard");
    }

    @GetMapping("/members")
    public String members(HttpServletRequest request, Model model) {
        return protectedView(request, model, "멤버", "members", "members");
    }

    @GetMapping("/posts")
    public String posts(HttpServletRequest request, Model model) {
        return protectedView(request, model, "게시물", "posts", "posts");
    }

    @GetMapping("/posts/new")
    public String newPost(HttpServletRequest request, Model model) {
        return protectedView(request, model, "새 게시물", "posts", "post-editor");
    }

    @GetMapping("/posts/{id}")
    public String post(
            @PathVariable String id,
            HttpServletRequest request,
            Model model) {
        model.addAttribute("postId", id);
        return protectedView(request, model, "게시물 편집", "posts", "post-editor");
    }

    @GetMapping("/profile")
    public String profile(HttpServletRequest request, Model model) {
        return protectedView(request, model, "내 프로필", "profile", "profile");
    }

    @GetMapping("/chat")
    public String chat(HttpServletRequest request, Model model) {
        return protectedView(request, model, "실시간 채팅", "chat", "chat");
    }

    private String protectedView(
            HttpServletRequest request,
            Model model,
            String title,
            String navigation,
            String view) {
        SessionResponse session = sessions.current(request);
        if (!session.authenticated()) {
            return "redirect:/access";
        }
        populate(model, title, navigation, session);
        return view;
    }

    private void populate(Model model, String title, String navigation, SessionResponse session) {
        model.addAttribute("projectName", "__WIZ_PROJECT_NAME__");
        model.addAttribute("pageTitle", title);
        model.addAttribute("activeNavigation", navigation);
        model.addAttribute("sessionUser", session);
    }
}
