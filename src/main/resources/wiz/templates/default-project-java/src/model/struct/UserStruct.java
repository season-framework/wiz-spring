import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import __WIZ_PACKAGE_ROOT__.domain.entity.UserEntity;
import __WIZ_PACKAGE_ROOT__.module.season.infrastructure.orm.Ids;
import __WIZ_PACKAGE_ROOT__.module.season.infrastructure.orm.Jpa;
import __WIZ_PACKAGE_ROOT__.module.season.security.PasswordHasher;
import com.wiz.runtime.WizContext;

import org.springframework.transaction.support.TransactionTemplate;

public final class UserStruct {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] AVATAR_COLORS = {
            "bg-indigo-100 text-indigo-700",
            "bg-pink-100 text-pink-700",
            "bg-green-100 text-green-700",
            "bg-amber-100 text-amber-700",
            "bg-cyan-100 text-cyan-700",
            "bg-violet-100 text-violet-700"
    };

    private final UserEntity.Repository users;
    private final TransactionTemplate transaction;

    public UserStruct(WizContext wiz, Jpa jpa) {
        this.users = new UserEntity.Repository(jpa.entityManager());
        this.transaction = jpa.transaction();
    }

    public Map<String, Object> authenticate(String email, String password) {
        return users.findByEmail(normalizeEmail(email))
                .filter(user -> PasswordHasher.matches(password, user.getPassword()))
                .map(this::dto)
                .orElse(null);
    }

    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return users.findById(id).map(this::dto).orElse(null);
    }

    public List<Map<String, Object>> list(String text, String role) {
        String normalizedText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String normalizedRole = role == null ? "" : role.trim();
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserEntity user : users.findAllByOrderByCreatedAsc()) {
            Map<String, Object> item = dto(user);
            if (!normalizedRole.isBlank() && !normalizedRole.equals(item.get("role"))) {
                continue;
            }
            if (!normalizedText.isBlank()
                    && !string(item.get("email")).toLowerCase(Locale.ROOT).contains(normalizedText)
                    && !string(item.get("name")).toLowerCase(Locale.ROOT).contains(normalizedText)) {
                continue;
            }
            item.put("avatarColor", AVATAR_COLORS[result.size() % AVATAR_COLORS.length]);
            item.put("joined", left(string(item.get("created")), 10));
            result.add(item);
        }
        return result;
    }

    public String create(Map<String, Object> data) {
        return transaction.execute(status -> {
            String now = now();
            UserEntity user = new UserEntity();
            user.setId(Ids.next());
            user.setEmail(normalizeEmail(data.get("email")));
            user.setPassword(PasswordHasher.hash(string(data.getOrDefault("password", "welcome1"))));
            user.setName(valueOrDefault(data.get("name"), user.getEmail().split("@", 2)[0]));
            user.setMobile(valueOrDefault(data.get("mobile"), ""));
            user.setRole(valueOrDefault(data.get("role"), "user"));
            user.setCreated(valueOrDefault(data.get("created"), now));
            user.setUpdated(valueOrDefault(data.get("updated"), now));
            users.save(user);
            return user.getId();
        });
    }

    public boolean existsEmail(String email) {
        return users.existsByEmail(normalizeEmail(email));
    }

    public void delete(String id) {
        if (id != null && !id.isBlank()) {
            transaction.executeWithoutResult(status -> users.deleteById(id));
        }
    }

    public int updateProfile(String id, String name, String mobile) {
        return transaction.execute(status -> users.findById(id).map(user -> {
            user.setName(name);
            user.setMobile(mobile == null ? "" : mobile);
            user.setUpdated(now());
            users.save(user);
            return 1;
        }).orElse(0));
    }

    public boolean changePassword(String id, String currentPassword, String newPassword) {
        return transaction.execute(status -> users.findById(id).map(user -> {
            if (!PasswordHasher.matches(currentPassword, user.getPassword())) {
                return false;
            }
            user.setPassword(PasswordHasher.hash(newPassword));
            user.setUpdated(now());
            users.save(user);
            return true;
        }).orElse(false));
    }

    public int count() {
        return (int) users.count();
    }

    public void seedDefaults() {
        seedUser("admin@example.com", "admin1234", "관리자", "010-0000-0000", "admin");
        seedUser("alice@example.com", "alice1234", "Alice Kim", "010-1000-0001", "user");
        seedUser("bob@example.com", "bob12345", "Bob Park", "010-1000-0002", "user");
        seedUser("carol@example.com", "carol123", "Carol Lee", "010-1000-0003", "editor");
        seedUser("dave@example.com", "dave1234", "Dave Choi", "010-1000-0004", "viewer");
    }

    public Map<String, Object> seedUser(String email, String password, String name, String mobile, String role) {
        return users.findByEmail(normalizeEmail(email))
                .map(this::dto)
                .orElseGet(() -> get(create(Map.of(
                        "email", email,
                        "password", password,
                        "name", name,
                        "mobile", mobile,
                        "role", role))));
    }

    private Map<String, Object> dto(UserEntity user) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("id", user.getId());
        item.put("email", user.getEmail());
        item.put("name", user.getName());
        item.put("mobile", user.getMobile());
        item.put("role", user.getRole());
        item.put("created", user.getCreated());
        item.put("updated", user.getUpdated());
        return item;
    }

    private String normalizeEmail(Object value) {
        return string(value).trim().toLowerCase(Locale.ROOT);
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
}
