package ai.pollinations.minecraft;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player request cooldown, so one player cannot drain the server's Pollen. */
public final class Cooldowns {

    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private volatile long cooldownMillis;

    public Cooldowns(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    public void setCooldownMillis(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    /** Returns 0 and records the use when allowed, otherwise the milliseconds left. */
    public long tryUse(UUID player, long now) {
        long[] left = {0};
        lastUse.compute(player, (id, last) -> {
            if (last != null && now - last < cooldownMillis) {
                left[0] = cooldownMillis - (now - last);
                return last;
            }
            return now;
        });
        return left[0];
    }

    public void clear(UUID player) {
        lastUse.remove(player);
    }
}
