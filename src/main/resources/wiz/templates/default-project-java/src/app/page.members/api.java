import java.util.Map;

import __WIZ_PACKAGE_ROOT__.model.Struct;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageMembersApi {

    public Object list(WizContext wiz) {
        Struct struct = wiz.models().get("struct", Struct.class);
        return struct.user().list(wiz.request().query("text", ""), wiz.request().query("role", ""));
    }

    public WizResult invite(WizContext wiz) {
        String email = wiz.request().query("email", "").trim();
        String role = wiz.request().query("role", "viewer").trim();
        if (email.isBlank()) {
            return wiz.response().status(400, Map.of("message", "이메일을 입력해주세요."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        if (struct.user().existsEmail(email.toLowerCase(java.util.Locale.ROOT))) {
            return wiz.response().status(400, Map.of("message", "이미 등록된 사용자입니다."));
        }
        struct.user().create(Map.of(
                "email", email,
                "password", "welcome1",
                "name", email.split("@", 2)[0],
                "role", role.isBlank() ? "viewer" : role));
        return wiz.response().status(200);
    }

    public WizResult detail(WizContext wiz) {
        String id = wiz.request().query("id", "");
        if (id.isBlank()) {
            return wiz.response().status(400, Map.of("message", "ID가 필요합니다."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        Map<String, Object> user = struct.user().get(id);
        if (user == null) {
            return wiz.response().status(404, Map.of("message", "사용자를 찾을 수 없습니다."));
        }
        return wiz.response().ok(user);
    }

    public WizResult remove(WizContext wiz) {
        String id = wiz.request().query("id", "");
        if (id.isBlank()) {
            return wiz.response().status(400, Map.of("message", "ID가 필요합니다."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        struct.user().delete(id);
        return wiz.response().status(200);
    }
}
