import __WIZ_PACKAGE_ROOT__.model.struct.UserStruct;
import __WIZ_PACKAGE_ROOT__.portal.post.model.PostStruct;
import __WIZ_PACKAGE_ROOT__.portal.season.model.orm.Jpa;
import com.wiz.runtime.WizContext;

public final class Struct {

    private final UserStruct user;
    private final PostStruct post;

    public Struct(WizContext wiz) {
        Jpa jpa = new Jpa(wiz);
        this.user = new UserStruct(wiz, jpa);
        this.post = new PostStruct(wiz, jpa);
        this.user.seedDefaults();
        this.post.seedDefaults(this.user);
    }

    public static void warmup(WizContext wiz) {
        new Struct(wiz);
    }

    public UserStruct user() {
        return user;
    }

    public PostStruct post() {
        return post;
    }
}
