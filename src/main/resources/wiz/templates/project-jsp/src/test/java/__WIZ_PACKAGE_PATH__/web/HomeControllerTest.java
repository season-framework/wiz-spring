package __WIZ_PACKAGE_ROOT__.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import __WIZ_PACKAGE_ROOT__.model.user.UserEntity;
import __WIZ_PACKAGE_ROOT__.model.user.UserRepository;
import __WIZ_PACKAGE_ROOT__.model.user.UserRole;
import __WIZ_PACKAGE_ROOT__.security.SessionContext;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.SessionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

class HomeControllerTest {

    private UserRepository users;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
    }

    @Test
    void anonymousVisitorsAreSentToTheAccessView() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HomeController controller = new HomeController(new SessionContext(request, users));

        assertEquals("redirect:/access", controller.home());
        assertEquals("redirect:/access", controller.dashboard(new ExtendedModelMap()));
    }

    @Test
    void accessRendersARealJspViewForAnonymousVisitors() {
        ExtendedModelMap model = new ExtendedModelMap();
        HomeController controller = new HomeController(
                new SessionContext(new MockHttpServletRequest(), users));

        assertEquals("access", controller.access(model));
        assertEquals("__WIZ_PROJECT_NAME__", model.get("projectName"));
        assertEquals("access", model.get("activeNavigation"));
    }

    @Test
    void authenticatedVisitorsReceiveTheSelectedViewAndSessionModel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserEntity user = new UserEntity(
                "user-1",
                "admin@example.com",
                "unused-test-hash",
                "관리자",
                "010-0000-0000",
                UserRole.ADMIN,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(users.findById("user-1")).thenReturn(java.util.Optional.of(user));
        SessionContext session = new SessionContext(request, users);
        session.login(user);
        HomeController controller = new HomeController(session);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("dashboard", controller.dashboard(model));
        assertEquals("dashboard", model.get("activeNavigation"));
        SessionView sessionView = (SessionView) model.get("sessionUser");
        assertEquals("admin@example.com", sessionView.email());
        assertEquals("redirect:/dashboard", controller.access(new ExtendedModelMap()));
    }

    @Test
    void postDetailRoutePassesThePathIdentifierToItsView() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserEntity user = new UserEntity(
                "user-1", "admin@example.com", "unused", "관리자", "", UserRole.ADMIN, Instant.now());
        when(users.findById("user-1")).thenReturn(java.util.Optional.of(user));
        SessionContext session = new SessionContext(request, users);
        session.login(user);
        HomeController controller = new HomeController(session);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("post-editor", controller.post("post-42", model));
        assertEquals("post-42", model.get("postId"));
        assertEquals("posts", model.get("activeNavigation"));
    }
}
