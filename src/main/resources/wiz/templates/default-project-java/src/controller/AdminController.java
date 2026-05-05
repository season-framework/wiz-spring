import com.wiz.dispatch.ControllerHook;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class AdminController implements ControllerHook {
    @Override
    public WizResult before(WizContext wiz) {
        wiz.response().data("session", wiz.session().toMap());
        return wiz.auth().requireAdmin(wiz);
    }
}