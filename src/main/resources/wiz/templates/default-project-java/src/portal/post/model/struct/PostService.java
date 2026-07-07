import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import __WIZ_PACKAGE_ROOT__.application.service.UserStruct;
import __WIZ_PACKAGE_ROOT__.module.post.domain.entity.PostEntity;
import __WIZ_PACKAGE_ROOT__.module.post.domain.entity.CommentEntity;
import __WIZ_PACKAGE_ROOT__.module.season.infrastructure.orm.Ids;
import __WIZ_PACKAGE_ROOT__.module.season.infrastructure.orm.Jpa;
import com.wiz.runtime.WizContext;

public final class PostService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final WizContext wiz;
    private final Jpa jpa;
    private final PostEntity.Repository posts;
    private final CommentEntity.Repository comments;

    public PostService(WizContext wiz, Jpa jpa) {
        this.wiz = wiz;
        this.jpa = jpa;
        this.posts = new PostEntity.Repository(jpa.entityManager());
        this.comments = new CommentEntity.Repository(jpa.entityManager());
    }

    public String create(Map<String, Object> data) {
        return jpa.transaction().execute(status -> {
            PostEntity post = new PostEntity();
            post.setId(Ids.next());
            applyWritable(post, data);
            if (string(post.getAuthorId()).isBlank()) {
                post.setAuthorId(sessionValue("id"));
            }
            if (string(post.getAuthorName()).isBlank()) {
                post.setAuthorName(sessionValue("name"));
            }
            if (string(post.getStatus()).isBlank()) {
                post.setStatus("draft");
            }
            String now = now();
            post.setCreated(valueOrDefault(data.get("created"), now));
            post.setUpdated(now);
            posts.save(post);
            return post.getId();
        });
    }

    public int update(Map<String, Object> data, String id) {
        return jpa.transaction().execute(status -> posts.findById(id).map(post -> {
            applyWritable(post, data);
            post.setUpdated(now());
            posts.save(post);
            return 1;
        }).orElse(0));
    }

    public int delete(String id) {
        return jpa.transaction().execute(status -> {
            if (!posts.existsById(id)) {
                return 0;
            }
            comments.deleteByPostId(id);
            posts.deleteById(id);
            return 1;
        });
    }

    public Map<String, Object> get(String id) {
        return posts.findById(id).map(this::format).orElse(null);
    }

    public List<Map<String, Object>> recent(int limit) {
        int safeLimit = Math.max(1, limit);
        return posts.findRecent(safeLimit)
                .stream()
                .map(this::format)
                .toList();
    }

    public SearchResult search(String text, String category, int page, int dump) {
        String normalizedText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String normalizedCategory = category == null ? "" : category.trim();
        List<Map<String, Object>> filtered = posts.findAllByOrderByCreatedDesc()
                .stream()
                .filter(row -> normalizedCategory.isBlank() || normalizedCategory.equals(row.getCategory()))
                .filter(row -> normalizedText.isBlank()
                        || string(row.getTitle()).toLowerCase(Locale.ROOT).contains(normalizedText))
                .map(this::format)
                .toList();
        int safeDump = dump < 1 ? 20 : dump;
        int safePage = Math.max(1, page);
        int from = Math.min(filtered.size(), (safePage - 1) * safeDump);
        int to = Math.min(filtered.size(), from + safeDump);
        return new SearchResult(filtered.subList(from, to), filtered.size());
    }

    public List<String> categories() {
        return posts.findDistinctCategories();
    }

    public int count(Map<String, Object> where) {
        if (where == null || where.isEmpty()) {
            return (int) posts.count();
        }
        if (where.size() == 1 && where.containsKey("status")) {
            return (int) posts.countByStatus(string(where.get("status")));
        }
        return (int) posts.findAll().stream()
                .filter(post -> where.entrySet().stream().allMatch(entry -> Objects.equals(field(post, entry.getKey()), entry.getValue())))
                .count();
    }

    public void seedDefaults(UserStruct users) {
        if (posts.count() > 0) {
            return;
        }
        Map<String, Object> admin = users.seedUser("admin@example.com", "admin1234", "관리자", "010-0000-0000", "admin");
        seedPost(admin, "Spring WIZ 시작하기", "공지사항", "published", "Spring WIZ Java 샘플 프로젝트의 첫 게시물입니다.");
        seedPost(admin, "App-local API 작성법", "가이드", "published", "각 App 디렉토리의 api.java에서 WizContext를 받아 API를 구현합니다.");
        seedPost(admin, "초대 기능 점검", "자유게시판", "draft", "멤버 초대와 권한 흐름을 검증하기 위한 임시 게시물입니다.");
    }

    private void seedPost(Map<String, Object> author, String title, String category, String status, String content) {
        create(Map.of(
                "title", title,
                "category", category,
                "status", status,
                "content", content,
                "author_id", string(author.get("id")),
                "author_name", string(author.get("name"))));
    }

    private void applyWritable(PostEntity post, Map<String, Object> data) {
        post.setTitle(valueOrDefault(data.get("title"), valueOrDefault(post.getTitle(), "Untitled")));
        post.setContent(valueOrDefault(data.get("content"), valueOrDefault(post.getContent(), "")));
        post.setCategory(valueOrDefault(data.get("category"), valueOrDefault(post.getCategory(), "")));
        post.setStatus(valueOrDefault(data.get("status"), valueOrDefault(post.getStatus(), "draft")));
        if (data.containsKey("author_id")) {
            post.setAuthorId(string(data.get("author_id")));
        }
        if (data.containsKey("author_name")) {
            post.setAuthorName(string(data.get("author_name")));
        }
    }

    private Map<String, Object> format(PostEntity post) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("id", post.getId());
        item.put("title", post.getTitle());
        item.put("content", post.getContent());
        item.put("category", post.getCategory());
        item.put("author_id", post.getAuthorId());
        item.put("author_name", post.getAuthorName());
        item.put("status", post.getStatus());
        item.put("created", post.getCreated());
        item.put("updated", post.getUpdated());
        item.put("author", valueOrDefault(post.getAuthorName(), ""));
        item.put("date", left(string(post.getCreated()), 10));
        item.put("summary", left(string(post.getContent()), 120));
        return item;
    }

    private Object field(PostEntity post, String key) {
        return switch (key) {
            case "id" -> post.getId();
            case "title" -> post.getTitle();
            case "category" -> post.getCategory();
            case "author_id" -> post.getAuthorId();
            case "author_name" -> post.getAuthorName();
            case "status" -> post.getStatus();
            case "created" -> post.getCreated();
            case "updated" -> post.getUpdated();
            default -> null;
        };
    }

    private String sessionValue(String key) {
        return wiz.session().get(key).map(Objects::toString).orElse("");
    }

    private String valueOrDefault(Object value, String defaultValue) {
        String text = string(value);
        return text.isBlank() ? defaultValue : text;
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private String left(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    public record SearchResult(List<Map<String, Object>> rows, int total) {
    }
}
