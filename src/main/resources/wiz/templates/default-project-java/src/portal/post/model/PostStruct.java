import com.wiz.project.main.model.struct.UserStruct;
import com.wiz.project.main.portal.post.model.struct.CommentService;
import com.wiz.project.main.portal.post.model.struct.PostService;
import com.wiz.runtime.WizContext;

public final class PostStruct {

    private final WizContext wiz;
    private final PostService post;
    private final CommentService comment;

    public PostStruct(WizContext wiz) {
        this.wiz = wiz;
        this.post = new PostService(wiz);
        this.comment = new CommentService(wiz);
    }

    public PostService post() {
        return post;
    }

    public CommentService comment() {
        return comment;
    }

    public String getUserId() {
        return wiz.session().get("id").map(Object::toString).orElse("");
    }

    public String getUserName() {
        return wiz.session().get("name").map(Object::toString).orElse("");
    }

    public boolean isAdmin() {
        return "admin".equals(wiz.session().get("role", ""));
    }

    public void seedDefaults(UserStruct users) {
        post.seedDefaults(users);
    }
}