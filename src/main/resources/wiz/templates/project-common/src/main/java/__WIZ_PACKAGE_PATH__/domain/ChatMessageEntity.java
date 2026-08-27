package __WIZ_PACKAGE_ROOT__.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, nullable = false)
    private String authorId;

    @Column(length = 100, nullable = false)
    private String authorName;

    @Column(length = 500, nullable = false)
    private String text;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(String authorId, String authorName, String text, Instant sentAt) {
        this.authorId = authorId;
        this.authorName = authorName;
        this.text = text;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getText() {
        return text;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
