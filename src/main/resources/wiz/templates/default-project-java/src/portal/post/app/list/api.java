import java.util.Map;

import __WIZ_PACKAGE_ROOT__.module.post.application.model.PostStruct;
import __WIZ_PACKAGE_ROOT__.module.post.application.service.PostService;
import com.wiz.runtime.WizContext;

public final class PortalPostListApi {

    public Object categories(WizContext wiz) {
        PostStruct struct = wiz.models().get("portal/post/struct", PostStruct.class);
        return struct.post().categories();
    }

    public Object search(WizContext wiz) {
        int page = parseInt(wiz.request().query("page", "1"), 1);
        int dump = parseInt(wiz.request().query("dump", "20"), 20);
        String text = wiz.request().query("text", "");
        String category = wiz.request().query("category", "");
        PostStruct struct = wiz.models().get("portal/post/struct", PostStruct.class);
        PostService.SearchResult result = struct.post().search(text, category, page, dump);
        return Map.of("rows", result.rows(), "total", result.total());
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}