import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "comments")
public class CommentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "post_id", nullable = false)
    private String postId;

    @Column(length = 2000, nullable = false)
    private String content;

    @Column(name = "author_id", nullable = false)
    private String authorId = "";

    @Column(name = "author_name", nullable = false)
    private String authorName = "";

    @Column(nullable = false)
    private String created;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public static final class Repository {

        private final EntityManager entityManager;

        public Repository(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        public List<CommentEntity> findByPostIdOrderByCreatedAsc(String postId) {
            return entityManager
                    .createQuery("select c from CommentEntity c where c.postId = :postId order by c.created asc", CommentEntity.class)
                    .setParameter("postId", postId)
                    .getResultList();
        }

        public long countByPostId(String postId) {
            return entityManager
                    .createQuery("select count(c) from CommentEntity c where c.postId = :postId", Long.class)
                    .setParameter("postId", postId)
                    .getSingleResult();
        }

        public void deleteByPostId(String postId) {
            entityManager
                    .createQuery("delete from CommentEntity c where c.postId = :postId")
                    .setParameter("postId", postId)
                    .executeUpdate();
        }

        public Optional<CommentEntity> findById(String id) {
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(entityManager.find(CommentEntity.class, id));
        }

        public boolean existsById(String id) {
            return findById(id).isPresent();
        }

        public CommentEntity save(CommentEntity comment) {
            return entityManager.merge(comment);
        }

        public void deleteById(String id) {
            findById(id).ifPresent(entityManager::remove);
        }
    }
}
