import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import __WIZ_PACKAGE_ROOT__.portal.post.model.db.CommentEntity;
import __WIZ_PACKAGE_ROOT__.portal.season.model.orm.Ids;
import __WIZ_PACKAGE_ROOT__.portal.season.model.orm.Jpa;
import com.wiz.runtime.WizContext;

public final class CommentService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final WizContext wiz;
    private final Jpa jpa;
    private final CommentEntity.Repository comments;

    public CommentService(WizContext wiz, Jpa jpa) {
        this.wiz = wiz;
        this.jpa = jpa;
        this.comments = new CommentEntity.Repository(jpa.entityManager());
    }

    public List<Map<String, Object>> list(String postId) {
        return comments.findByPostIdOrderByCreatedAsc(postId).stream().map(this::dto).toList();
    }

    public String create(Map<String, Object> data) {
        return jpa.transaction().execute(status -> {
            CommentEntity comment = new CommentEntity();
            comment.setId(Ids.next());
            comment.setPostId(string(data.get("post_id")));
            comment.setContent(string(data.getOrDefault("content", "")));
            comment.setAuthorId(sessionValue("id"));
            comment.setAuthorName(sessionValue("name"));
            comment.setCreated(LocalDateTime.now().format(TIMESTAMP));
            comments.save(comment);
            return comment.getId();
        });
    }

    public int delete(String id) {
        return jpa.transaction().execute(status -> {
            if (!comments.existsById(id)) {
                return 0;
            }
            comments.deleteById(id);
            return 1;
        });
    }

    public int count(String postId) {
        return (int) comments.countByPostId(postId);
    }

    private Map<String, Object> dto(CommentEntity comment) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("id", comment.getId());
        item.put("post_id", comment.getPostId());
        item.put("content", comment.getContent());
        item.put("author_id", comment.getAuthorId());
        item.put("author_name", comment.getAuthorName());
        item.put("created", comment.getCreated());
        return item;
    }

    private String sessionValue(String key) {
        return wiz.session().get(key).map(Objects::toString).orElse("");
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
