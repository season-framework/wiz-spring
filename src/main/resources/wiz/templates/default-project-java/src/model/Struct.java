import com.wiz.project.main.model.struct.UserStruct;
import com.wiz.project.main.portal.post.model.PostStruct;
import com.wiz.runtime.WizContext;

public final class Struct {

    private final UserStruct user;
    private final PostStruct post;

    public Struct(WizContext wiz) {
        this.user = new UserStruct(wiz);
        this.post = new PostStruct(wiz);
        this.user.seedDefaults();
        this.post.seedDefaults(this.user);
    }

    public UserStruct user() {
        return user;
    }

    public PostStruct post() {
        return post;
    }
}