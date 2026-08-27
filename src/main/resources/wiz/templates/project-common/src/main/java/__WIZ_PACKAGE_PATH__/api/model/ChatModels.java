package __WIZ_PACKAGE_ROOT__.api.model;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ChatModels {

    private ChatModels() {
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 500) String text) {
    }

    public record ChatMessageResponse(
            long id,
            String authorId,
            String authorName,
            String text,
            Instant sentAt) {
    }
}
