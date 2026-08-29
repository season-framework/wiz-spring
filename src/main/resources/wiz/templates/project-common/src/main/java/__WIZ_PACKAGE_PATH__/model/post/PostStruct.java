package __WIZ_PACKAGE_ROOT__.model.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import __WIZ_PACKAGE_ROOT__.exception.ApiException;
import __WIZ_PACKAGE_ROOT__.model.user.UserRole;
import __WIZ_PACKAGE_ROOT__.security.SessionContext;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class PostStruct {

    private final PostRepository posts;
    private final SessionContext session;

    public PostStruct(PostRepository posts, SessionContext session) {
        this.posts = posts;
        this.session = session;
    }

    public PageView search(String text, String category, int page, int size) {
        String normalizedText = text == null ? "" : text.trim();
        String normalizedCategory = category == null ? "" : category.trim();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize);
        Page<PostEntity> result = posts.search(normalizedText, normalizedCategory, pageable);
        return new PageView(
                result.getContent().stream().map(this::view).toList(),
                result.getTotalElements(),
                safePage,
                safeSize,
                result.getTotalPages());
    }

    public List<String> categories() {
        return posts.findDistinctCategories();
    }

    public View get(String id) {
        return view(find(id));
    }

    @Transactional
    public View create(String title, String content, String category, String status) {
        AuthenticatedUser author = session.requireUser();
        Instant now = Instant.now();
        PostEntity post = new PostEntity(
                UUID.randomUUID().toString(),
                title.trim(),
                value(content),
                value(category),
                author.id(),
                author.name(),
                parseStatus(status, PostStatus.DRAFT),
                now);
        return view(posts.save(post));
    }

    @Transactional
    public View update(String id, String title, String content, String category, String status) {
        AuthenticatedUser actor = session.requireUser();
        PostEntity post = find(id);
        requireOwnerOrAdmin(actor, post);
        post.update(
                title.trim(),
                value(content),
                value(category),
                parseStatus(status, post.getStatus()),
                Instant.now());
        return view(post);
    }

    @Transactional
    public void delete(String id) {
        AuthenticatedUser actor = session.requireUser();
        PostEntity post = find(id);
        requireOwnerOrAdmin(actor, post);
        posts.delete(post);
    }

    private void requireOwnerOrAdmin(AuthenticatedUser actor, PostEntity post) {
        if (actor.role() != UserRole.ADMIN && !actor.id().equals(post.getAuthorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "작성자 또는 관리자만 게시물을 변경할 수 있습니다.");
        }
    }

    private PostEntity find(String id) {
        return posts.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다."));
    }

    private PostStatus parseStatus(String value, PostStatus defaultStatus) {
        try {
            return PostStatus.from(value, defaultStatus);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private View view(PostEntity post) {
        return new View(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                summary(post.getContent()),
                post.getCategory(),
                post.getAuthorId(),
                post.getAuthorName(),
                post.getStatus().value(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    private String summary(String content) {
        String compact = value(content).replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    public record View(
            String id,
            String title,
            String content,
            String summary,
            String category,
            String authorId,
            String authorName,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PageView(
            List<View> items,
            long total,
            int page,
            int size,
            int totalPages) {
    }
}
