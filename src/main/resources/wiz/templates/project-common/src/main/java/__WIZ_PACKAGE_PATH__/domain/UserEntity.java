package __WIZ_PACKAGE_ROOT__.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sample_users")
public class UserEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(length = 320, nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 40, nullable = false)
    private String mobile;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {
    }

    public UserEntity(
            String id,
            String email,
            String passwordHash,
            String name,
            String mobile,
            UserRole role,
            Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.mobile = mobile;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void updateProfile(String name, String mobile, Instant now) {
        this.name = name;
        this.mobile = mobile;
        this.updatedAt = now;
    }

    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getMobile() {
        return mobile;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
