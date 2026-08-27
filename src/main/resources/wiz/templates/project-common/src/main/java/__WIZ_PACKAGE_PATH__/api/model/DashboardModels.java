package __WIZ_PACKAGE_ROOT__.api.model;

import java.time.Instant;
import java.util.List;

public final class DashboardModels {

    private DashboardModels() {
    }

    public record DashboardResponse(
            String project,
            List<DashboardStat> stats,
            List<RecentPost> recent) {
    }

    public record DashboardStat(
            String key,
            String label,
            long value,
            int change,
            String icon,
            String tone) {
    }

    public record RecentPost(
            String id,
            String title,
            String category,
            String authorName,
            String status,
            Instant createdAt) {
    }
}
