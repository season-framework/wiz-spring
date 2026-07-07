package com.wiz.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.config.WizRuntimeProperties;
import com.wiz.config.WizRedirectProperties;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.dispatch.AppApiDispatcher;
import com.wiz.domain.ModelRegistry;
import com.wiz.http.ResponseEnvelope;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectWarmupService;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRuntime;
import com.wiz.socket.ProjectSocketDispatcher;
import com.wiz.socket.SocketEventResult;
import com.wiz.socket.SocketNamespace;
import com.wiz.socket.SocketRoomRegistry;
import com.wiz.socket.SocketSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

import tools.jackson.databind.ObjectMapper;

class JavaSampleProjectTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaSampleProjectBuildsAndRunsMainApis() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());
        assertTrue(Files.exists(project.bundleRoot().resolve("app-api.jar")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/realtime/socket/PageChatSocketController.class")));

        SocketRoomRegistry rooms = new SocketRoomRegistry();
        ProjectSocketDispatcher socketDispatcher = new ProjectSocketDispatcher(new PathService(workspace), rooms);
        SocketNamespace chatNamespace = new SocketNamespace("page.chat");
        MockHttpSession chatHttpSession = new MockHttpSession();
        chatHttpSession.setAttribute("id", "admin");
        chatHttpSession.setAttribute("role", "admin");
        SocketSession chatSession = new SocketSession("chat-1", chatNamespace, Map.of(), chatHttpSession, "127.0.0.1");
        assertTrue(socketDispatcher.dispatch(chatSession, "join", Map.of("room", "lobby")).accepted());
        SocketEventResult chat = socketDispatcher.dispatch(chatSession, "send", Map.of("room", "lobby", "name", "Admin", "text", "hello"));
        assertEquals("chat.message", chat.event());
        assertEquals("lobby", chat.room());
        assertTrue(chat.message().contains("hello"));

        AppApiDispatcher dispatcher = dispatcher(workspace);
        MockHttpSession session = new MockHttpSession();

        WizResult anonymousDashboard = dispatch(dispatcher, WizRequest.builder().method("POST").build(), "page.dashboard", "overview");
        assertEquals(401, anonymousDashboard.httpStatus());

        ResponseEnvelope missingLogin = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("email", "")
                .formParam("password", "")
                .build(), "page.access", "login"));
        assertEquals(400, missingLogin.code());
        assertEquals("이메일과 비밀번호를 입력해주세요.", dataMap(missingLogin.data()).get("message"));

        ResponseEnvelope login = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .jsonBody("{\"email\":\"admin@example.com\",\"password\":\"admin1234\"}")
                .build(), "page.access", "login"));
        assertEquals(200, login.code());
        assertEquals("admin", session.getAttribute("role"));

        Map<String, Object> dashboard = dataMap(dispatch(dispatcher, request(session), "page.dashboard", "overview"));
        List<Map<String, Object>> stats = dataList(dashboard.get("stats"));
        assertEquals(4, stats.size());
        assertEquals("📄", stats.getFirst().get("icon"));
        List<Map<String, Object>> recent = dataList(dashboard.get("recent"));
        assertFalse(recent.isEmpty());
        assertTrue(recent.getFirst().containsKey("avatarColor"));

        List<Map<String, Object>> members = dataList(envelope(dispatch(dispatcher, request(session), "page.members", "list")).data());
        assertTrue(members.size() >= 5);
        assertTrue(members.stream().anyMatch(member -> "admin@example.com".equals(member.get("email")) && !member.containsKey("password")));

        ResponseEnvelope invite = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("email", "eve@example.com")
                .formParam("role", "viewer")
                .build(), "page.members", "invite"));
        assertEquals(200, invite.code());

        List<Map<String, Object>> invited = dataList(envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .queryParam("text", "eve@example.com")
                .build(), "page.members", "list")).data());
        assertEquals(1, invited.size());
        String invitedId = invited.getFirst().get("id").toString();

        Map<String, Object> memberDetail = dataMap(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .queryParam("id", invitedId)
                .build(), "page.members", "detail"));
        assertEquals("eve@example.com", memberDetail.get("email"));

        ResponseEnvelope mypage = envelope(dispatch(dispatcher, request(session), "page.mypage", "get"));
        assertEquals("admin@example.com", dataMap(mypage.data()).get("email"));

        ResponseEnvelope updateProfile = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("name", "Admin Updated")
                .formParam("mobile", "010-9999-0000")
                .build(), "page.mypage", "update_profile"));
        assertEquals(200, updateProfile.code());
        assertEquals("Admin Updated", session.getAttribute("name"));

        ResponseEnvelope changePassword = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("current_password", "admin1234")
                .formParam("new_password", "admin4321")
                .build(), "page.mypage", "change_password"));
        assertEquals(200, changePassword.code());

        List<?> categories = dataList(envelope(dispatch(dispatcher, request(session), "portal.post.list", "categories")).data());
        assertTrue(categories.contains("공지사항"));

        Map<String, Object> search = dataMap(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .queryParam("page", "1")
                .queryParam("dump", "5")
                .build(), "portal.post.list", "search"));
        List<Map<String, Object>> posts = dataList(search.get("rows"));
        assertFalse(posts.isEmpty());
        String postId = posts.getFirst().get("id").toString();

        Map<String, Object> postDetail = dataMap(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .queryParam("id", postId)
                .build(), "portal.post.detail", "get"));
        assertNotNull(postDetail.get("summary"));

        String newPostJson = objectMapper.writeValueAsString(Map.of(
                "id", "new",
                "title", "Integration Post",
                "content", "Created from the Java sample integration test",
                "category", "공지사항"));
        Map<String, Object> savedPost = dataMap(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("data", newPostJson)
                .build(), "portal.post.detail", "save"));
        assertEquals("Integration Post", savedPost.get("title"));
        assertEquals("draft", savedPost.get("status"));
        assertEquals("Admin Updated", savedPost.get("author"));

        ResponseEnvelope deletePost = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("id", savedPost.get("id").toString())
                .build(), "portal.post.detail", "delete"));
        assertEquals(200, deletePost.code());

        ResponseEnvelope removeMember = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("id", invitedId)
                .build(), "page.members", "remove"));
        assertEquals(200, removeMember.code());
    }

    @Test
    void defaultProjectTemplateBuildsAndRunsMainApis() throws Exception {
        Path workspace = tempDir.resolve("default-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        ProjectRuntimeCache cache = warmup(workspace);
        assertTrue(Files.exists(project.root().resolve("data/app.db")));

        AppApiDispatcher dispatcher = dispatcher(workspace, cache);
        MockHttpSession session = new MockHttpSession();
        ResponseEnvelope login = envelope(dispatch(dispatcher, WizRequest.builder()
                .method("POST")
                .session(session)
                .formParam("email", "admin@example.com")
                .formParam("password", "admin1234")
                .build(), "page.access", "login"));
        assertEquals(200, login.code());
        assertEquals("admin", session.getAttribute("role"));

        Map<String, Object> dashboard = dataMap(dispatch(dispatcher, request(session), "page.dashboard", "overview"));
        assertEquals(4, dataList(dashboard.get("stats")).size());
        assertFalse(dataList(dashboard.get("recent")).isEmpty());
    }

    @Test
    void javaSampleProjectContainsOnlyJavaBackendFiles() throws Exception {
        ProjectContext project = createEmbeddedSampleProject("java-only-workspace", "main");
        try (var paths = Files.walk(project.root())) {
            assertTrue(paths.noneMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".py")));
        }
    }

    private AppApiDispatcher dispatcher(Path workspace) {
        return new AppApiDispatcher(new WizRuntime(new ProjectRegistry(new PathService(workspace))));
    }

    private AppApiDispatcher dispatcher(Path workspace, ProjectRuntimeCache cache) {
        ProjectRegistry registry = new ProjectRegistry(new PathService(workspace));
        return new AppApiDispatcher(new WizRuntime(registry, new ModelRegistry(cache), new WizRedirectProperties(), cache));
    }

    private ProjectRuntimeCache warmup(Path workspace) {
        ProjectRuntimeCache cache = new ProjectRuntimeCache();
        new ProjectWarmupService(
                new ProjectRegistry(new PathService(workspace)),
                cache,
                new ModelRegistry(cache),
                new WizRedirectProperties(),
                new WizRuntimeProperties())
                .run(null);
        return cache;
    }

    private WizRequest request(MockHttpSession session) {
        return WizRequest.builder().method("POST").session(session).build();
    }

    private WizResult dispatch(AppApiDispatcher dispatcher, WizRequest request, String appId, String function) {
        return dispatcher.dispatch(request, appId, function, "");
    }

    private ResponseEnvelope envelope(WizResult result) {
        assertTrue(result.entity() instanceof ResponseEnvelope, "Expected response envelope for " + result.entity());
        return (ResponseEnvelope) result.entity();
    }

    private Map<String, Object> dataMap(WizResult result) {
        return dataMap(envelope(result).data());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(Object value) {
        assertTrue(value instanceof Map<?, ?>, "Expected map data for " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> dataList(Object value) {
        assertTrue(value instanceof List<?>, "Expected list data for " + value);
        return (List<T>) value;
    }

    private ProjectContext createEmbeddedSampleProject(String workspaceName, String projectName) throws Exception {
        Path workspace = tempDir.resolve(workspaceName);
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createApp(null, null);
    }

    private void removeAngularSource(ProjectContext project) throws Exception {
        Path angular = project.sourceRoot().resolve("angular");
        if (!Files.exists(angular)) {
            return;
        }
        try (var paths = Files.walk(angular)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
