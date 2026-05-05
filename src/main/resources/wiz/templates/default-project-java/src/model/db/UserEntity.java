import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String mobile = "";

    @Column(nullable = false)
    private String role = "user";

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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

        public Optional<UserEntity> findByEmail(String email) {
            return entityManager
                    .createQuery("select u from UserEntity u where u.email = :email", UserEntity.class)
                    .setParameter("email", email)
                    .setMaxResults(1)
                    .getResultList()
                    .stream()
                    .findFirst();
        }

        public Optional<UserEntity> findById(String id) {
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(entityManager.find(UserEntity.class, id));
        }

        public List<UserEntity> findAllByOrderByCreatedAsc() {
            return entityManager
                    .createQuery("select u from UserEntity u order by u.created asc", UserEntity.class)
                    .getResultList();
        }

        public boolean existsByEmail(String email) {
            return count("select count(u) from UserEntity u where u.email = :email", "email", email) > 0;
        }

        public UserEntity save(UserEntity user) {
            return entityManager.merge(user);
        }

        public void deleteById(String id) {
            findById(id).ifPresent(entityManager::remove);
        }

        public long count() {
            return count("select count(u) from UserEntity u", null, null);
        }

        private long count(String query, String parameterName, Object value) {
            var typedQuery = entityManager.createQuery(query, Long.class);
            if (parameterName != null) {
                typedQuery.setParameter(parameterName, value);
            }
            return typedQuery.getSingleResult();
        }
    }
}
