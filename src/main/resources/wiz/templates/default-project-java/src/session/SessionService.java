import jakarta.servlet.http.HttpSession;

public class SessionService extends com.wiz.session.SessionService {

    public SessionService(HttpSession httpSession) {
        super(httpSession);
    }
}
