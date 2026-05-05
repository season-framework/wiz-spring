import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "posts")
public class PostEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(length = 4000, nullable = false)
    private String content;

    @Column(nullable = false)
    private String category = "";

    @Column(name = "author_id", nullable = false)
    private String authorId = "";

    @Column(name = "author_name", nullable = false)
    private String authorName = "";

    @Column(nullable = false)
    private String status = "draft";

    @Column(nullable = false)
    private String created;

    @Column(nullable = false)
    private String updated;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getUpdated() {
        return updated;
    }

    public void setUpdated(String updated) {
        this.updated = updated;
    }

    public static final class Repository {

        private final EntityManager entityManager;

        public Repository(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        public Optional<PostEntity> findById(String id) {
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(entityManager.find(PostEntity.class, id));
        }

        public boolean existsById(String id) {
            return findById(id).isPresent();
        }

        public PostEntity save(PostEntity post) {
            return entityManager.merge(post);
        }

        public void deleteById(String id) {
            findById(id).ifPresent(entityManager::remove);
        }

        public List<PostEntity> findAll() {
            return entityManager
                    .createQuery("select p from PostEntity p", PostEntity.class)
                    .getResultList();
        }

        public List<PostEntity> findRecent(int limit) {
            return entityManager
                    .createQuery("select p from PostEntity p order by p.created desc", PostEntity.class)
                    .setMaxResults(limit)
                    .getResultList();
        }

        public List<PostEntity> findAllByOrderByCreatedDesc() {
            return entityManager
                    .createQuery("select p from PostEntity p order by p.created desc", PostEntity.class)
                    .getResultList();
        }

        public List<String> findDistinctCategories() {
            return entityManager
                    .createQuery("select distinct p.category from PostEntity p where p.category <> '' order by p.category", String.class)
                    .getResultList();
        }

        public long count() {
            return entityManager.createQuery("select count(p) from PostEntity p", Long.class).getSingleResult();
        }

        public long countByStatus(String status) {
            return entityManager
                    .createQuery("select count(p) from PostEntity p where p.status = :status", Long.class)
                    .setParameter("status", status)
                    .getSingleResult();
        }
    }
}
