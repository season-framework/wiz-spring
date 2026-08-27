package __WIZ_PACKAGE_ROOT__.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import __WIZ_PACKAGE_ROOT__.api.model.AuthModels.SessionResponse;
import __WIZ_PACKAGE_ROOT__.domain.UserEntity;
import __WIZ_PACKAGE_ROOT__.domain.UserRole;
import __WIZ_PACKAGE_ROOT__.repository.UserRepository;
import __WIZ_PACKAGE_ROOT__.service.SessionAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

class HomeControllerTest {

    private SessionAuthService sessions;
    private UserRepository users;
    private HomeController controller;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        sessions = new SessionAuthService(users);
        controller = new HomeController(sessions);
    }

    @Test
    void anonymousVisitorsAreSentToTheAccessView() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals("redirect:/access", controller.home(request));
        assertEquals("redirect:/access", controller.dashboard(request, new ExtendedModelMap()));
    }

    @Test
    void accessRendersARealJspViewForAnonymousVisitors() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("access", controller.access(new MockHttpServletRequest(), model));
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
        sessions.login(request, user);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("dashboard", controller.dashboard(request, model));
        assertEquals("dashboard", model.get("activeNavigation"));
        SessionResponse session = (SessionResponse) model.get("sessionUser");
        assertEquals("admin@example.com", session.email());
        assertEquals("redirect:/dashboard", controller.access(request, new ExtendedModelMap()));
    }

    @Test
    void postDetailRoutePassesThePathIdentifierToItsView() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserEntity user = new UserEntity(
                "user-1", "admin@example.com", "unused", "관리자", "", UserRole.ADMIN, Instant.now());
        when(users.findById("user-1")).thenReturn(java.util.Optional.of(user));
        sessions.login(request, user);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("post-editor", controller.post("post-42", request, model));
        assertEquals("post-42", model.get("postId"));
        assertEquals("posts", model.get("activeNavigation"));
    }
}
