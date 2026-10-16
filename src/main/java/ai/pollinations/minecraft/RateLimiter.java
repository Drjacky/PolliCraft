package ai.pollinations.minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player limiter: a minimum gap between requests plus a cap per sliding window (one hour in the plugin),
 * so one player cannot drain the server's Pollen. Ported from tomdacatto's pollinations-minecraft plugin.
 * Methods are synchronized because callers run on several threads.
 */
public final class RateLimiter {

    public record Decision(boolean allowed, long retryAfterMillis) {
    }

    private final long windowMillis;
    private final Map<UUID, Deque<Long>> hits = new HashMap<>();
    private long minGapMillis;
    private int maxPerWindow;

    public RateLimiter(long minGapMillis, int maxPerWindow, long windowMillis) {
        this.minGapMillis = minGapMillis;
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
    }

    /** Changes the limits on /polli reload without forgetting what players already used. 0 or less = no cap. */
    public synchronized void setLimits(long minGapMillis, int maxPerWindow) {
        this.minGapMillis = minGapMillis;
        this.maxPerWindow = maxPerWindow;
    }

    /** Records the request and allows it, or reports how long to wait. Denied requests are not recorded. */
    public synchronized Decision tryAcquire(UUID player, long nowMillis) {
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
        if (maxPerWindow > 0 && recent.size() >= maxPerWindow) {
            return new Decision(false, recent.peekFirst() + windowMillis - nowMillis);
        }
        recent.addLast(nowMillis);
        return new Decision(true, 0);
    }
}
