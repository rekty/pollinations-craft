package ai.pollinations.craft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player limiter: a minimum gap between requests plus a cap per sliding window.
 * Methods are synchronized because callers run on several threads.
 *
 * <p>Taken from tomdacatto's "Pollinations for Paper" (RateLimiter.java, MIT).
 */
final class RateLimiter {

    record Decision(boolean allowed, long retryAfterMillis) {}

    private final long minGapMillis;
    private final int maxPerWindow;
    private final long windowMillis;
    private final Map<UUID, Deque<Long>> hits = new HashMap<>();

    RateLimiter(long minGapMillis, int maxPerWindow, long windowMillis) {
        this.minGapMillis = minGapMillis;
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
    }

    /** Records the request and allows it, or reports how long to wait. Denied requests are not recorded. */
    synchronized Decision tryAcquire(UUID player, long nowMillis) {
        Deque<Long> recent = hits.computeIfAbsent(player, id -> new ArrayDeque<>());
        while (!recent.isEmpty() && nowMillis - recent.peekFirst() >= windowMillis) {
            recent.pollFirst();
        }
        if (!recent.isEmpty()) {
            long sinceLast = nowMillis - recent.peekLast();
            if (sinceLast < minGapMillis) {
                return new Decision(false, minGapMillis - sinceLast);
            }
        }
        if (recent.size() >= maxPerWindow) {
            return new Decision(false, recent.peekFirst() + windowMillis - nowMillis);
        }
        recent.addLast(nowMillis);
        return new Decision(true, 0);
    }
}
