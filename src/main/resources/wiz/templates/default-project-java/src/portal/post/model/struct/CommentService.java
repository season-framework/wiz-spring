import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wiz.project.main.portal.season.model.orm.OrmModel;
import com.wiz.project.main.portal.season.model.orm.OrmService;
import com.wiz.project.main.portal.season.model.orm.RowsQuery;
import com.wiz.runtime.WizContext;

public final class CommentService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final WizContext wiz;
    private final OrmModel db;

    public CommentService(WizContext wiz) {
        this.wiz = wiz;
        this.db = new OrmService(wiz).use("comment");
    }

    public List<Map<String, Object>> list(String postId) {
        return db.rows(RowsQuery.builder()
                .where("post_id", postId)
                .orderBy("created")
                .order("ASC")
                .build());
    }

    public String create(Map<String, Object> data) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("post_id", data.get("post_id"));
        item.put("content", data.getOrDefault("content", ""));
        item.put("author_id", sessionValue("id"));
        item.put("author_name", sessionValue("name"));
        item.put("created", LocalDateTime.now().format(TIMESTAMP));
        return db.insert(item);
    }

    public int delete(String id) {
        return db.delete(Map.of("id", id));
    }

    public int count(String postId) {
        return db.count(Map.of("post_id", postId));
    }

    private String sessionValue(String key) {
        return wiz.session().get(key).map(Objects::toString).orElse("");
    }
}
