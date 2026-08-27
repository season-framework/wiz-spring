package __WIZ_PACKAGE_ROOT__.api;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.api.model.ChatModels.ChatMessageResponse;
import __WIZ_PACKAGE_ROOT__.api.model.ChatModels.SendMessageRequest;
import __WIZ_PACKAGE_ROOT__.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ApiController("/chat")
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/messages")
    public List<ChatMessageResponse> messages(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        return chat.recent(request, limit);
    }

    @PostMapping("/messages")
    public ChatMessageResponse send(
            @Valid @RequestBody SendMessageRequest input,
            HttpServletRequest request) {
        return chat.send(request, input.text());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return chat.stream(request, Math.max(after, eventCursor(lastEventId)));
    }

    private long eventCursor(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
