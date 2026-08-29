package __WIZ_PACKAGE_ROOT__.model;

import __WIZ_PACKAGE_ROOT__.model.chat.ChatStruct;
import __WIZ_PACKAGE_ROOT__.model.dashboard.DashboardStruct;
import __WIZ_PACKAGE_ROOT__.model.post.PostStruct;
import __WIZ_PACKAGE_ROOT__.model.user.UserStruct;
import org.springframework.stereotype.Component;

@Component
public record Struct(
        UserStruct user,
        PostStruct post,
        ChatStruct chat,
        DashboardStruct dashboard) {
}
