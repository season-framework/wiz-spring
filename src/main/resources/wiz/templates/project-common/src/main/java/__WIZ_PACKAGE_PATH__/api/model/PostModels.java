package __WIZ_PACKAGE_ROOT__.api.model;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PostModels {

    private PostModels() {
    }

    public record PostRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String content,
            @Size(max = 60) String category,
            @Size(max = 20) String status) {
    }

    public record PostResponse(
            String id,
            String title,
            String content,
            String summary,
            String category,
            String authorId,
            String authorName,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PostPage(
            List<PostResponse> items,
            long total,
            int page,
            int size,
            int totalPages) {
    }
}
