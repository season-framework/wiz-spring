package __WIZ_PACKAGE_ROOT__.controller;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.model.Struct;
import __WIZ_PACKAGE_ROOT__.model.chat.ChatStruct.MessageView;
import __WIZ_PACKAGE_ROOT__.web.ApiController;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ApiController("/chat")
public class ChatController {

    private final Struct struct;

    public ChatController(Struct struct) {
        this.struct = struct;
    }

    @GetMapping("/messages")
    public List<MessageView> messages(@RequestParam(defaultValue = "50") int limit) {
        return struct.chat().recent(limit);
    }

    @PostMapping("/messages")
    public MessageView send(@Valid @RequestBody SendMessageRequest input) {
        return struct.chat().send(input.text());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return struct.chat().stream(Math.max(after, eventCursor(lastEventId)));
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

    public record SendMessageRequest(@NotBlank @Size(max = 500) String text) {
    }
}
