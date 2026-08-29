package __WIZ_PACKAGE_ROOT__.model.chat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import __WIZ_PACKAGE_ROOT__.model.chat.ChatStruct.MessageView;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ChatEventHub {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1_000L;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter connect(Supplier<List<MessageView>> replay) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("chat.connected").data("connected"));
            for (MessageView message : replay.get()) {
                send(emitter, message);
            }
        } catch (IOException | RuntimeException exception) {
            emitters.remove(emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(MessageView message) {
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, message);
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }

    private void send(SseEmitter emitter, MessageView message) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(message.id()))
                .name("chat.message")
                .data(message));
    }
}
