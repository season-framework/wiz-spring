package __WIZ_PACKAGE_ROOT__.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import __WIZ_PACKAGE_ROOT__.api.model.PostModels.PostPage;
import __WIZ_PACKAGE_ROOT__.api.model.PostModels.PostRequest;
import __WIZ_PACKAGE_ROOT__.api.model.PostModels.PostResponse;
import __WIZ_PACKAGE_ROOT__.domain.PostEntity;
import __WIZ_PACKAGE_ROOT__.domain.PostStatus;
import __WIZ_PACKAGE_ROOT__.domain.UserRole;
import __WIZ_PACKAGE_ROOT__.repository.PostRepository;
import __WIZ_PACKAGE_ROOT__.service.SessionAuthService.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository posts;
    private final SessionAuthService sessions;

    public PostService(PostRepository posts, SessionAuthService sessions) {
        this.posts = posts;
        this.sessions = sessions;
    }

    public PostPage search(String text, String category, int page, int size) {
        String normalizedText = text == null ? "" : text.trim();
        String normalizedCategory = category == null ? "" : category.trim();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize);
        Page<PostEntity> result = posts.search(normalizedText, normalizedCategory, pageable);
        return new PostPage(
                result.getContent().stream().map(this::response).toList(),
                result.getTotalElements(),
                safePage,
                safeSize,
                result.getTotalPages());
    }

    public List<String> categories() {
        return posts.findDistinctCategories();
    }

    public PostResponse get(String id) {
        return response(find(id));
    }

    @Transactional
    public PostResponse create(HttpServletRequest request, PostRequest input) {
        AuthenticatedUser author = sessions.requireUser(request);
        Instant now = Instant.now();
        PostEntity post = new PostEntity(
                UUID.randomUUID().toString(),
                input.title().trim(),
                value(input.content()),
                value(input.category()),
                author.id(),
                author.name(),
                parseStatus(input.status(), PostStatus.DRAFT),
                now);
        return response(posts.save(post));
    }

    @Transactional
    public PostResponse update(HttpServletRequest request, String id, PostRequest input) {
        AuthenticatedUser actor = sessions.requireUser(request);
        PostEntity post = find(id);
        requireOwnerOrAdmin(actor, post);
        post.update(
                input.title().trim(),
                value(input.content()),
                value(input.category()),
                parseStatus(input.status(), post.getStatus()),
                Instant.now());
        return response(post);
    }

    @Transactional
    public void delete(HttpServletRequest request, String id) {
        AuthenticatedUser actor = sessions.requireUser(request);
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

    private PostResponse response(PostEntity post) {
        return new PostResponse(
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
}
