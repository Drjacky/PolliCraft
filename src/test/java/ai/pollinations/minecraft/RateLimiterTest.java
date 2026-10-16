package ai.pollinations.minecraft;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ported from tomdacatto's RateLimiterTest, plus reload and "no cap". */
class RateLimiterTest {

    private static final long HOUR = 3_600_000L;

    @Test
    void enforcesTheMinimumGapBetweenRequests() {
        RateLimiter limiter = new RateLimiter(5_000, 100, HOUR);
        UUID alice = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(alice, 0).allowed());
        RateLimiter.Decision early = limiter.tryAcquire(alice, 2_000);
        assertFalse(early.allowed());
        assertEquals(3_000, early.retryAfterMillis());
        assertTrue(limiter.tryAcquire(alice, 5_000).allowed());
    }

    @Test
    void capsRequestsPerWindowAndFreesSlotsWhenHitsAgeOut() {
        RateLimiter limiter = new RateLimiter(0, 2, 60_000);
        UUID alice = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(alice, 0).allowed());
        assertTrue(limiter.tryAcquire(alice, 1_000).allowed());
        RateLimiter.Decision capped = limiter.tryAcquire(alice, 10_000);
        assertFalse(capped.allowed());
        assertEquals(50_000, capped.retryAfterMillis());
        assertTrue(limiter.tryAcquire(alice, 60_000).allowed());
    }

    @Test
    void deniedRequestsAreNotCountedAgainstTheWindow() {
        RateLimiter limiter = new RateLimiter(10_000, 5, HOUR);
        UUID alice = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(alice, 0).allowed());
        for (long t = 1_000; t < 10_000; t += 1_000) {
            assertFalse(limiter.tryAcquire(alice, t).allowed());
        }
        assertTrue(limiter.tryAcquire(alice, 10_000).allowed());
    }

    @Test
    void playersAreLimitedIndependently() {
        RateLimiter limiter = new RateLimiter(60_000, 10, HOUR);

        assertTrue(limiter.tryAcquire(UUID.randomUUID(), 0).allowed());
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), 0).allowed());
    }

    @Test
    void reloadKeepsUsageAndZeroMeansNoCap() {
        RateLimiter limiter = new RateLimiter(0, 1, HOUR);
        UUID alice = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(alice, 0).allowed());
        assertFalse(limiter.tryAcquire(alice, 1_000).allowed());
        limiter.setLimits(0, 2);
        assertTrue(limiter.tryAcquire(alice, 2_000).allowed());
        assertFalse(limiter.tryAcquire(alice, 3_000).allowed());
        limiter.setLimits(0, 0);
        assertTrue(limiter.tryAcquire(alice, 4_000).allowed());
    }
}
