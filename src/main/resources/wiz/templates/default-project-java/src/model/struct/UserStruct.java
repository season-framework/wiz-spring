import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wiz.project.main.portal.season.model.orm.OrmModel;
import com.wiz.project.main.portal.season.model.orm.OrmService;
import com.wiz.project.main.portal.season.model.orm.PasswordHasher;
import com.wiz.project.main.portal.season.model.orm.RowsQuery;
import com.wiz.runtime.WizContext;

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

    private final OrmModel db;

    public UserStruct(WizContext wiz) {
        this.db = new OrmService(wiz).use("user");
    }

    public OrmModel db() {
        return db;
    }

    public Map<String, Object> authenticate(String email, String password) {
        Map<String, Object> user = db.get("email", normalizeEmail(email));
        if (user == null || !PasswordHasher.matches(password, string(user.get("password")))) {
            return null;
        }
        return dto(user);
    }

    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Map<String, Object> user = db.get("id", id);
        return user == null ? null : dto(user);
    }

    public List<Map<String, Object>> list(String text, String role) {
        String normalizedText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String normalizedRole = role == null ? "" : role.trim();
        List<Map<String, Object>> rows = db.rows(RowsQuery.builder().orderBy("created").order("ASC").build());
        ArrayList<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> user = dto(row);
            if (!normalizedRole.isBlank() && !normalizedRole.equals(user.get("role"))) {
                continue;
            }
            if (!normalizedText.isBlank()
                    && !string(user.get("email")).toLowerCase(Locale.ROOT).contains(normalizedText)
                    && !string(user.get("name")).toLowerCase(Locale.ROOT).contains(normalizedText)) {
                continue;
            }
            user.put("avatarColor", AVATAR_COLORS[users.size() % AVATAR_COLORS.length]);
            user.put("joined", left(string(user.get("created")), 10));
            users.add(user);
        }
        return users;
    }

    public String create(Map<String, Object> data) {
        String now = now();
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("email", normalizeEmail(data.get("email")));
        item.put("password", PasswordHasher.hash(string(data.getOrDefault("password", "welcome1"))));
        item.put("name", valueOrDefault(data.get("name"), item.get("email").toString().split("@", 2)[0]));
        item.put("mobile", valueOrDefault(data.get("mobile"), ""));
        item.put("role", valueOrDefault(data.get("role"), "user"));
        item.put("created", valueOrDefault(data.get("created"), now));
        item.put("updated", valueOrDefault(data.get("updated"), now));
        return db.insert(item);
    }

    public int updateProfile(String id, String name, String mobile) {
        return db.update(Map.of(
                "name", name,
                "mobile", mobile == null ? "" : mobile,
                "updated", now()), Map.of("id", id));
    }

    public boolean changePassword(String id, String currentPassword, String newPassword) {
        Map<String, Object> user = db.get("id", id);
        if (user == null || !PasswordHasher.matches(currentPassword, string(user.get("password")))) {
            return false;
        }
        db.update(Map.of("password", PasswordHasher.hash(newPassword), "updated", now()), Map.of("id", id));
        return true;
    }

    public int count() {
        return db.count(Map.of());
    }

    public void seedDefaults() {
        seedUser("admin@example.com", "admin1234", "관리자", "010-0000-0000", "admin");
        seedUser("alice@example.com", "alice1234", "Alice Kim", "010-1000-0001", "user");
        seedUser("bob@example.com", "bob12345", "Bob Park", "010-1000-0002", "user");
        seedUser("carol@example.com", "carol123", "Carol Lee", "010-1000-0003", "editor");
        seedUser("dave@example.com", "dave1234", "Dave Choi", "010-1000-0004", "viewer");
    }

    public Map<String, Object> seedUser(String email, String password, String name, String mobile, String role) {
        Map<String, Object> existing = db.get("email", normalizeEmail(email));
        if (existing != null) {
            return dto(existing);
        }
        String id = create(Map.of(
                "email", email,
                "password", password,
                "name", name,
                "mobile", mobile,
                "role", role));
        return get(id);
    }

    private Map<String, Object> dto(Map<String, Object> row) {
        return new LinkedHashMap<>(db.toDto(row));
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
