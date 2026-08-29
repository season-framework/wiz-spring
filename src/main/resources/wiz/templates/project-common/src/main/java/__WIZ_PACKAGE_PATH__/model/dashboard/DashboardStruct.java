package __WIZ_PACKAGE_ROOT__.model.dashboard;

import java.time.Instant;
import java.util.List;

import __WIZ_PACKAGE_ROOT__.model.post.PostRepository;
import __WIZ_PACKAGE_ROOT__.model.post.PostStatus;
import __WIZ_PACKAGE_ROOT__.model.user.UserRepository;
import __WIZ_PACKAGE_ROOT__.security.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class DashboardStruct {

    private final PostRepository posts;
    private final UserRepository users;
    private final SessionContext session;

    public DashboardStruct(PostRepository posts, UserRepository users, SessionContext session) {
        this.posts = posts;
        this.users = users;
        this.session = session;
    }

    public View overview() {
        session.requireUser();
        return new View(
                "__WIZ_PROJECT_NAME__",
                List.of(
                        stat("posts", "전체 게시물", posts.count(), "document", "blue"),
                        stat("published", "공개 게시물", posts.countByStatus(PostStatus.PUBLISHED), "check", "green"),
                        stat("draft", "임시저장", posts.countByStatus(PostStatus.DRAFT), "pencil", "amber"),
                        stat("members", "멤버", users.count(), "users", "purple")),
                posts.findTop5ByOrderByCreatedAtDesc().stream()
                        .map(post -> new RecentPostView(
                                post.getId(),
                                post.getTitle(),
                                post.getCategory(),
                                post.getAuthorName(),
                                post.getStatus().value(),
                                post.getCreatedAt()))
                        .toList());
    }

    private StatView stat(String key, String label, long value, String icon, String tone) {
        return new StatView(key, label, value, 0, icon, tone);
    }

    public record View(
            String project,
            List<StatView> stats,
            List<RecentPostView> recent) {
    }

    public record StatView(
            String key,
            String label,
            long value,
            int change,
            String icon,
            String tone) {
    }

    public record RecentPostView(
            String id,
            String title,
            String category,
            String authorName,
            String status,
            Instant createdAt) {
    }
}
