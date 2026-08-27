package __WIZ_PACKAGE_ROOT__.service;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.api.model.DashboardModels.DashboardResponse;
import __WIZ_PACKAGE_ROOT__.api.model.DashboardModels.DashboardStat;
import __WIZ_PACKAGE_ROOT__.api.model.DashboardModels.RecentPost;
import __WIZ_PACKAGE_ROOT__.domain.PostStatus;
import __WIZ_PACKAGE_ROOT__.repository.PostRepository;
import __WIZ_PACKAGE_ROOT__.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final PostRepository posts;
    private final UserRepository users;
    private final SessionAuthService sessions;

    public DashboardService(PostRepository posts, UserRepository users, SessionAuthService sessions) {
        this.posts = posts;
        this.users = users;
        this.sessions = sessions;
    }

    public DashboardResponse overview(HttpServletRequest request) {
        sessions.requireUser(request);
        return new DashboardResponse(
                "__WIZ_PROJECT_NAME__",
                List.of(
                        stat("posts", "전체 게시물", posts.count(), "document", "blue"),
                        stat("published", "공개 게시물", posts.countByStatus(PostStatus.PUBLISHED), "check", "green"),
                        stat("draft", "임시저장", posts.countByStatus(PostStatus.DRAFT), "pencil", "amber"),
                        stat("members", "멤버", users.count(), "users", "purple")),
                posts.findTop5ByOrderByCreatedAtDesc().stream()
                        .map(post -> new RecentPost(
                                post.getId(),
                                post.getTitle(),
                                post.getCategory(),
                                post.getAuthorName(),
                                post.getStatus().value(),
                                post.getCreatedAt()))
                        .toList());
    }

    private DashboardStat stat(String key, String label, long value, String icon, String tone) {
        return new DashboardStat(key, label, value, 0, icon, tone);
    }
}
