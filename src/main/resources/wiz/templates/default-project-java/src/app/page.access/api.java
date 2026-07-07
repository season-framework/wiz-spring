import java.util.Locale;
import java.util.Map;

import __WIZ_PACKAGE_ROOT__.application.model.Struct;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageAccessApi {

    public WizResult login(WizContext wiz) {
        String email = wiz.request().query("email", "").trim().toLowerCase(Locale.ROOT);
        String password = wiz.request().query("password", "");
        if (email.isBlank() || password.isBlank()) {
            return wiz.response().status(400, Map.of("message", "이메일과 비밀번호를 입력해주세요."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        Map<String, Object> user = struct.user().authenticate(email, password);
        if (user == null) {
            return wiz.response().status(401, Map.of("message", "이메일 또는 비밀번호가 올바르지 않습니다."));
        }
        wiz.session().set(Map.of(
                "id", user.get("id"),
                "email", user.get("email"),
                "name", user.get("name"),
                "role", user.get("role")));
        return wiz.response().ok(user);
    }
}
