package __WIZ_PACKAGE_ROOT__.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import __WIZ_PACKAGE_ROOT__.api.model.ChatModels.ChatMessageResponse;
import __WIZ_PACKAGE_ROOT__.domain.ChatMessageEntity;
import __WIZ_PACKAGE_ROOT__.repository.ChatMessageRepository;
import __WIZ_PACKAGE_ROOT__.service.SessionAuthService.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Transactional(readOnly = true)
public class ChatService {

    private final ChatMessageRepository messages;
    private final SessionAuthService sessions;
    private final ChatEventHub events;

    public ChatService(ChatMessageRepository messages, SessionAuthService sessions, ChatEventHub events) {
        this.messages = messages;
        this.sessions = sessions;
        this.events = events;
    }

    public List<ChatMessageResponse> recent(HttpServletRequest request, int limit) {
        sessions.requireUser(request);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        ArrayList<ChatMessageResponse> result = new ArrayList<>(messages
                .findAllByOrderByIdDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::response)
                .toList());
        Collections.reverse(result);
        return result;
    }

    @Transactional
    public ChatMessageResponse send(HttpServletRequest request, String text) {
        AuthenticatedUser author = sessions.requireUser(request);
        ChatMessageResponse response = response(messages.save(new ChatMessageEntity(
                author.id(),
                author.name(),
                text.trim(),
                Instant.now())));
        publishAfterCommit(response);
        return response;
    }

    public SseEmitter stream(HttpServletRequest request, long afterId) {
        sessions.requireUser(request);
        long cursor = Math.max(0, afterId);
        return events.connect(() -> messages.findByIdGreaterThanOrderByIdAsc(cursor).stream()
                .map(this::response)
                .toList());
    }

    private ChatMessageResponse response(ChatMessageEntity message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getAuthorId(),
                message.getAuthorName(),
                message.getText(),
                message.getSentAt());
    }

    private void publishAfterCommit(ChatMessageResponse message) {
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
}
