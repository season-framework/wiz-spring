package __WIZ_PACKAGE_ROOT__.model.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import __WIZ_PACKAGE_ROOT__.security.SessionContext;
import __WIZ_PACKAGE_ROOT__.security.SessionContext.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@Transactional(readOnly = true)
public class ChatStruct {

    private final ChatMessageRepository messages;
    private final SessionContext session;
    private final ChatEventHub events;

    public ChatStruct(ChatMessageRepository messages, SessionContext session, ChatEventHub events) {
        this.messages = messages;
        this.session = session;
        this.events = events;
    }

    public List<MessageView> recent(int limit) {
        session.requireUser();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        ArrayList<MessageView> result = new ArrayList<>(messages
                .findAllByOrderByIdDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::view)
                .toList());
        Collections.reverse(result);
        return result;
    }

    @Transactional
    public MessageView send(String text) {
        AuthenticatedUser author = session.requireUser();
        MessageView response = view(messages.save(new ChatMessageEntity(
                author.id(),
                author.name(),
                text.trim(),
                Instant.now())));
        publishAfterCommit(response);
        return response;
    }

    public SseEmitter stream(long afterId) {
        session.requireUser();
        long cursor = Math.max(0, afterId);
        return events.connect(() -> messages.findByIdGreaterThanOrderByIdAsc(cursor).stream()
                .map(this::view)
                .toList());
    }

    private MessageView view(ChatMessageEntity message) {
        return new MessageView(
                message.getId(),
                message.getAuthorId(),
                message.getAuthorName(),
                message.getText(),
                message.getSentAt());
    }

    private void publishAfterCommit(MessageView message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publish(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publish(message);
            }
        });
    }

    public record MessageView(
            long id,
            String authorId,
            String authorName,
            String text,
            Instant sentAt) {
    }
}
