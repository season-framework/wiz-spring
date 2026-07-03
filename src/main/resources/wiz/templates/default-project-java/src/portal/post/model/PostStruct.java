import __WIZ_PACKAGE_ROOT__.model.struct.UserStruct;
import __WIZ_PACKAGE_ROOT__.portal.post.model.struct.CommentService;
import __WIZ_PACKAGE_ROOT__.portal.post.model.struct.PostService;
import __WIZ_PACKAGE_ROOT__.portal.season.model.orm.Jpa;
import com.wiz.runtime.WizContext;

public final class PostStruct {

    private final WizContext wiz;
    private final PostService post;
    private final CommentService comment;

    public PostStruct(WizContext wiz) {
        this(wiz, new Jpa(wiz), true);
    }

    public PostStruct(WizContext wiz, Jpa jpa) {
        this(wiz, jpa, false);
    }

    private PostStruct(WizContext wiz, Jpa jpa, boolean seed) {
        this.wiz = wiz;
        this.post = new PostService(wiz, jpa);
        this.comment = new CommentService(wiz, jpa);
        if (seed) {
            UserStruct users = new UserStruct(wiz, jpa);
            users.seedDefaults();
            seedDefaults(users);
        }
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
