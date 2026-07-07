import java.util.LinkedHashMap;
import java.util.Map;

import __WIZ_PACKAGE_ROOT__.module.post.application.model.PostStruct;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class PortalPostDetailApi {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public WizResult get(WizContext wiz) {
        String id = wiz.request().query("id", "");
        if (id.isBlank()) {
            return wiz.response().status(400, Map.of("message", "ID가 필요합니다."));
        }
        PostStruct struct = wiz.models().get("portal/post/struct", PostStruct.class);
        Map<String, Object> post = struct.post().get(id);
        if (post == null) {
            return wiz.response().status(404, Map.of("message", "게시물을 찾을 수 없습니다."));
        }
        return wiz.response().ok(post);
    }

    public WizResult save(WizContext wiz) throws Exception {
        Map<String, Object> data = parseData(wiz.request().query("data", "{}"));
        String postId = data.getOrDefault("id", "").toString();
        PostStruct struct = wiz.models().get("portal/post/struct", PostStruct.class);
        if (postId.isBlank() || postId.equals("new")) {
            postId = struct.post().create(data);
        } else {
            struct.post().update(data, postId);
        }
        return wiz.response().ok(struct.post().get(postId));
    }

    public WizResult delete(WizContext wiz) {
        String id = wiz.request().query("id", "");
        if (id.isBlank()) {
            return wiz.response().status(400, Map.of("message", "ID가 필요합니다."));
        }
        PostStruct struct = wiz.models().get("portal/post/struct", PostStruct.class);
        struct.post().delete(id);
        return wiz.response().status(200);
    }

    private Map<String, Object> parseData(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
        }));
    }
}