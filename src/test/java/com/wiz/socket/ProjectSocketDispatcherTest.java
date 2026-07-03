package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

class ProjectSocketDispatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void dispatchesProjectSocketJavaThroughCompiledBundle() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        java.nio.file.Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), dashboardSocketJava());
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        SocketRoomRegistry rooms = new SocketRoomRegistry();
        ProjectSocketDispatcher dispatcher = new ProjectSocketDispatcher(new PathService(workspace), rooms);
        SocketNamespace namespace = new SocketNamespace("page.dashboard");
        SocketSession session = authenticatedSocket("sid-1", namespace);

        assertTrue(dispatcher.dispatch(session, "connect", Map.of()).accepted());
        assertTrue(dispatcher.dispatch(session, "join", Map.of("id", "room-1")).accepted());
        assertTrue(rooms.contains(namespace, "room-1", "sid-1"));
        assertFalse(dispatcher.dispatch(session, "missing", Map.of()).accepted());
    }

    @Test
    void appliesAppControllerPolicyBeforeSocketDispatch() throws Exception {
        Path workspace = tempDir.resolve("auth-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        java.nio.file.Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), dashboardSocketJava());
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        ProjectSocketDispatcher dispatcher = new ProjectSocketDispatcher(new PathService(workspace), new SocketRoomRegistry());
        SocketNamespace namespace = new SocketNamespace("page.dashboard");

        assertFalse(dispatcher.dispatch(new SocketSession("sid-1", namespace), "connect", Map.of()).accepted());
        assertTrue(dispatcher.dispatch(authenticatedSocket("sid-2", namespace), "connect", Map.of()).accepted());
    }

    @Test
    void reloadsProjectSocketHandlerAfterRebuildWithoutNewDispatcher() throws Exception {
        Path workspace = tempDir.resolve("reload-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Path socketSource = project.appRoot().resolve("page.dashboard/socket.java");
        java.nio.file.Files.writeString(socketSource, versionSocketJava("one"));
        BuildResult firstBuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(firstBuild.success(), firstBuild.message());

        ProjectSocketDispatcher dispatcher = new ProjectSocketDispatcher(new PathService(workspace), new SocketRoomRegistry());
        SocketSession session = authenticatedSocket("sid-1", new SocketNamespace("page.dashboard"));
        assertTrue(dispatcher.dispatch(session, "version", Map.of()).message().contains("one"));

        java.nio.file.Files.writeString(socketSource, versionSocketJava("two"));
        BuildResult secondBuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(secondBuild.success(), secondBuild.message());

        assertTrue(dispatcher.dispatch(session, "version", Map.of()).message().contains("two"));
    }

    private SocketSession authenticatedSocket(String id, SocketNamespace namespace) {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute("id", "user-1");
        httpSession.setAttribute("role", "user");
        return new SocketSession(id, namespace, Map.of(), httpSession, "127.0.0.1");
    }

    private String dashboardSocketJava() {
        return "import java.util.Map;\n"
                + "import com.wiz.socket.SocketController;\n"
                + "import com.wiz.socket.SocketEventHandler;\n"
                + "import com.wiz.socket.SocketEventResult;\n"
                + "import com.wiz.socket.SocketRoomRegistry;\n"
                + "import com.wiz.socket.SocketSession;\n\n"
                + "public final class PageDashboardSocketController implements SocketController {\n"
                + "    public String appId() { return \"page.dashboard\"; }\n"
                + "    public Map<String, SocketEventHandler> handlers() {\n"
                + "        return Map.of(\"connect\", this::connect, \"join\", this::join);\n"
                + "    }\n"
                + "    private SocketEventResult connect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        return new SocketEventResult(true, \"connect\", \"connected\");\n"
                + "    }\n"
                + "    private SocketEventResult join(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        String room = payload.get(\"id\").toString();\n"
                + "        rooms.join(session, room);\n"
                + "        return new SocketEventResult(true, \"join\", room);\n"
                + "    }\n"
                + "}\n";
    }

    private String versionSocketJava(String version) {
        return "import java.util.Map;\n"
                + "import com.wiz.socket.SocketController;\n"
                + "import com.wiz.socket.SocketEventHandler;\n"
                + "import com.wiz.socket.SocketEventResult;\n"
                + "import com.wiz.socket.SocketRoomRegistry;\n"
                + "import com.wiz.socket.SocketSession;\n\n"
                + "public final class PageDashboardSocketController implements SocketController {\n"
                + "    public String appId() { return \"page.dashboard\"; }\n"
                + "    public Map<String, SocketEventHandler> handlers() { return Map.of(\"version\", this::version); }\n"
                + "    private SocketEventResult version(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        return new SocketEventResult(true, \"version\", \"" + version + "\");\n"
                + "    }\n"
                + "}\n";
    }
}
