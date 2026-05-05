import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.wiz.project.main.portal.season.model.orm.OrmModel;
import com.wiz.project.main.portal.season.model.orm.OrmService;
import com.wiz.project.main.portal.season.model.orm.RowsQuery;
import com.wiz.project.main.model.struct.UserStruct;
import com.wiz.runtime.WizContext;

public final class PostService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final WizContext wiz;
    private final OrmModel db;
    private final OrmModel comments;

    public PostService(WizContext wiz) {
        this.wiz = wiz;
        OrmService orm = new OrmService(wiz);
        this.db = orm.use("post");
        this.comments = orm.use("comment");
    }

    public OrmModel db() {
        return db;
    }

    public String create(Map<String, Object> data) {
        String now = now();
        LinkedHashMap<String, Object> item = writablePost(data);
        item.put("author_id", sessionValue("id"));
        item.put("author_name", sessionValue("name"));
        if (string(item.get("status")).isBlank()) {
            item.put("status", "draft");
        }
        item.put("created", valueOrDefault(data.get("created"), now));
        item.put("updated", now);
        return db.insert(item);
    }

    public int update(Map<String, Object> data, String id) {
        LinkedHashMap<String, Object> item = writablePost(data);
        item.remove("id");
        item.remove("created");
        item.put("updated", now());
        return db.update(item, Map.of("id", id));
    }

    public int delete(String id) {
        comments.delete(Map.of("post_id", id));
        return db.delete(Map.of("id", id));
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> row = db.get("id", id);
        return row == null ? null : format(row);
    }

    public List<Map<String, Object>> recent(int limit) {
        return db.rows(RowsQuery.builder().orderBy("created").order("DESC").page(1).dump(limit).build())
                .stream()
                .map(this::format)
                .toList();
    }

    public SearchResult search(String text, String category, int page, int dump) {
        String normalizedText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String normalizedCategory = category == null ? "" : category.trim();
        List<Map<String, Object>> filtered = db.rows(RowsQuery.builder().orderBy("created").order("DESC").build())
                .stream()
                .filter(row -> normalizedCategory.isBlank() || normalizedCategory.equals(row.get("category")))
                .filter(row -> normalizedText.isBlank()
                        || string(row.get("title")).toLowerCase(Locale.ROOT).contains(normalizedText))
                .map(this::format)
                .toList();
        int safeDump = dump < 1 ? 20 : dump;
        int safePage = Math.max(1, page);
        int from = Math.min(filtered.size(), (safePage - 1) * safeDump);
        int to = Math.min(filtered.size(), from + safeDump);
        return new SearchResult(filtered.subList(from, to), filtered.size());
    }

    public List<String> categories() {
        return db.rows(RowsQuery.builder().fields("category").build())
                .stream()
                .map(row -> string(row.get("category")).trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    public int count(Map<String, Object> where) {
        return db.count(where);
    }

    public void seedDefaults(UserStruct users) {
        if (db.count(Map.of()) > 0) {
            return;
        }
        Map<String, Object> admin = users.seedUser("admin@example.com", "admin1234", "관리자", "010-0000-0000", "admin");
        seedPost(admin, "Spring WIZ 시작하기", "공지사항", "published", "Spring WIZ Java 샘플 프로젝트의 첫 게시물입니다.");
        seedPost(admin, "App-local API 작성법", "가이드", "published", "각 App 디렉토리의 api.java에서 WizContext를 받아 API를 구현합니다.");
        seedPost(admin, "초대 기능 점검", "자유게시판", "draft", "멤버 초대와 권한 흐름을 검증하기 위한 임시 게시물입니다.");
    }

    private void seedPost(Map<String, Object> author, String title, String category, String status, String content) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("category", category);
        item.put("status", status);
        item.put("content", content);
        item.put("author_id", string(author.get("id")));
        item.put("author_name", string(author.get("name")));
        item.put("created", now());
        item.put("updated", now());
        db.insert(item);
    }

    private LinkedHashMap<String, Object> writablePost(Map<String, Object> data) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        for (String key : List.of("id", "title", "content", "category", "author_id", "author_name", "status", "created", "updated")) {
            if (data.containsKey(key) && data.get(key) != null) {
                item.put(key, data.get(key));
            }
        }
        item.putIfAbsent("title", "Untitled");
        item.putIfAbsent("content", "");
        item.putIfAbsent("category", "");
        return item;
    }

    private Map<String, Object> format(Map<String, Object> row) {
        LinkedHashMap<String, Object> post = new LinkedHashMap<>(row);
        post.put("author", valueOrDefault(post.get("author_name"), ""));
        post.put("date", left(string(post.get("created")), 10));
        post.put("summary", left(string(post.get("content")), 120));
        return post;
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
