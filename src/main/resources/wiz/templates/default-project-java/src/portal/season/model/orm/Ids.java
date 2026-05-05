import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class Ids {

    private static final String POOL = "abcdefghijklmnopqrstuvwxyz0123456789";

    private Ids() {
    }

    public static String next() {
        StringBuilder builder = new StringBuilder();
        builder.append(Instant.now().toEpochMilli()).append('-');
        for (int index = 0; index < 16; index++) {
            builder.append(POOL.charAt(ThreadLocalRandom.current().nextInt(POOL.length())));
        }
        return builder.toString();
    }
}
