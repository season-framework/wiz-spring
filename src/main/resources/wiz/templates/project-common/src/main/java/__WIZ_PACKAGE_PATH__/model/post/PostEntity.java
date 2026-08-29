package __WIZ_PACKAGE_ROOT__.model.post;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_posts")
public class PostEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 10_000, nullable = false)
    private String content;

    @Column(length = 60, nullable = false)
    private String category;

    @Column(name = "author_id", length = 36, nullable = false)
    private String authorId;

    @Column(name = "author_name", length = 100, nullable = false)
    private String authorName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private PostStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PostEntity() {
    }

    public PostEntity(
            String id,
            String title,
            String content,
            String category,
            String authorId,
            String authorName,
            PostStatus status,
            Instant createdAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.category = category;
        this.authorId = authorId;
        this.authorName = authorName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void update(String title, String content, String category, PostStatus status, Instant now) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.status = status;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getCategory() {
        return category;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public PostStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
