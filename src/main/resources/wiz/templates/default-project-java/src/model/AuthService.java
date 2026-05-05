import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public class AuthService extends com.wiz.session.AuthService {

    /*
     * Project-local auth extension.
     *
     * Override these methods when the project needs a custom auth policy:
     * - check(WizContext): return current login state for /auth/check.
     * - logout(WizContext): clear session/cookies and redirect or return JSON.
     * - requireUser(WizContext): return null when access is allowed, or a WizResult to block.
     * - requireAdmin(WizContext): same as requireUser, but for admin-only routes.
     * - oidcPlaceholder(WizContext), samlPlaceholder(WizContext): replace with real provider flows.
     */

    @Override
    public WizResult check(WizContext context) {
        return super.check(context);
    }
}
