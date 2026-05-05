import jakarta.servlet.http.HttpSession;

public class SessionService extends com.wiz.session.SessionService {

    /*
     * Project-local session extension.
     *
     * Methods inherited from com.wiz.session.SessionService:
     * - has(key), get(key), get(key, defaultValue)
     * - set(key, value), set(Map<String, ?> values)
     * - delete(key), clear(), invalidate()
     * - toMap(): expose current session values as an immutable Map
     * - userId(): Optional<String> backed by the "id" session key
     */

    public SessionService(HttpSession httpSession) {
        super(httpSession);
    }
}
