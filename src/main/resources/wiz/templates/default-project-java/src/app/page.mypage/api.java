import java.util.Map;

import __WIZ_PACKAGE_ROOT__.application.model.Struct;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageMypageApi {

    public WizResult get(WizContext wiz) {
        String userId = wiz.session().userId().orElse("");
        if (userId.isBlank()) {
            return wiz.response().status(401, Map.of("message", "로그인이 필요합니다."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        Map<String, Object> user = struct.user().get(userId);
        if (user == null) {
            return wiz.response().status(404, Map.of("message", "사용자를 찾을 수 없습니다."));
        }
        return wiz.response().ok(user);
    }

    public WizResult update_profile(WizContext wiz) {
        String userId = wiz.session().userId().orElse("");
        if (userId.isBlank()) {
            return wiz.response().status(401, Map.of("message", "로그인이 필요합니다."));
        }
        String name = wiz.request().query("name", "").trim();
        String mobile = wiz.request().query("mobile", "").trim();
        if (name.isBlank()) {
            return wiz.response().status(400, Map.of("message", "이름을 입력해주세요."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        struct.user().updateProfile(userId, name, mobile);
        wiz.session().set("name", name);
        return wiz.response().status(200);
    }

    public WizResult change_password(WizContext wiz) {
        String userId = wiz.session().userId().orElse("");
        if (userId.isBlank()) {
            return wiz.response().status(401, Map.of("message", "로그인이 필요합니다."));
        }
        String currentPassword = wiz.request().query("current_password", "");
        String newPassword = wiz.request().query("new_password", "");
        if (currentPassword.isBlank()) {
            return wiz.response().status(400, Map.of("message", "현재 비밀번호를 입력해주세요."));
        }
        if (newPassword.isBlank()) {
            return wiz.response().status(400, Map.of("message", "새 비밀번호를 입력해주세요."));
        }
        Struct struct = wiz.models().get("struct", Struct.class);
        if (!struct.user().changePassword(userId, currentPassword, newPassword)) {
            return wiz.response().status(400, Map.of("message", "현재 비밀번호가 올바르지 않습니다."));
        }
        return wiz.response().status(200);
    }
}