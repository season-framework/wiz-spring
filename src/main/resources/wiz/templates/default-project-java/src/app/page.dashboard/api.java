import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import __WIZ_PACKAGE_ROOT__.model.Struct;
import __WIZ_PACKAGE_ROOT__.portal.post.model.PostStruct;
import com.wiz.runtime.WizContext;

public final class PageDashboardApi {

    private static final String[] RECENT_AVATAR_COLORS = {
            "bg-indigo-100 text-indigo-700",
            "bg-pink-100 text-pink-700",
            "bg-green-100 text-green-700",
            "bg-amber-100 text-amber-700",
            "bg-cyan-100 text-cyan-700"
    };

    public Object overview(WizContext wiz) {
        Struct struct = wiz.models().get("struct", Struct.class);
        PostStruct posts = struct.post();
        int totalPosts = posts.post().count(Map.of());
        int publishedPosts = posts.post().count(Map.of("status", "published"));
        int draftPosts = posts.post().count(Map.of("status", "draft"));
        int totalMembers = struct.user().count();
        return Map.of(
                "stats", List.of(
                        stat("전체 게시물", totalPosts, "📄", "bg-blue-50"),
                        stat("공개 게시물", publishedPosts, "✅", "bg-green-50"),
                        stat("임시저장", draftPosts, "✏️", "bg-yellow-50"),
                        stat("멤버", totalMembers, "👥", "bg-purple-50")),
                "recent", recentWithAvatarColors(posts.post().recent(5)));
    }

    private Map<String, Object> stat(String label, int value, String icon, String bgColor) {
        return Map.of("label", label, "value", Integer.toString(value), "change", 0, "icon", icon, "bgColor", bgColor);
    }

    private List<Map<String, Object>> recentWithAvatarColors(List<Map<String, Object>> recent) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < recent.size(); index++) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(recent.get(index));
            row.put("avatarColor", RECENT_AVATAR_COLORS[index % RECENT_AVATAR_COLORS.length]);
            rows.add(row);
        }
        return rows;
    }
}
