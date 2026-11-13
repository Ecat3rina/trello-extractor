package com.ecaterina;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    /** Fake clock: sleeping just moves time forward. */
    static class FakeTime implements RateLimiter.TimeSource {
        long now = 0;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleepNanos(long nanos) {
            sleeps.add(nanos);
            now += nanos;
        }
    }

    @Test
    void allowsRequestsUpToTheLimitWithoutWaiting() throws Exception {
        FakeTime time = new FakeTime();
        RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(10), time);

        limiter.acquire();
        limiter.acquire();
        limiter.acquire();

        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void waitsUntilTheWindowFreesUp() throws Exception {
        FakeTime time = new FakeTime();
        RateLimiter limiter = new RateLimiter(2, Duration.ofSeconds(10), time);

        limiter.acquire();               // t = 0
        time.now = Duration.ofSeconds(4).toNanos();
        limiter.acquire();               // t = 4 s
        limiter.acquire();               // must wait until t = 10 s

        assertEquals(List.of(Duration.ofSeconds(6).toNanos()), time.sleeps);
        assertEquals(Duration.ofSeconds(10).toNanos(), time.now);
    }

    @Test
    void neverExceedsLimitInAnyWindow() throws Exception {
        FakeTime time = new FakeTime();
        int max = 5;
        long window = Duration.ofSeconds(10).toNanos();
        RateLimiter limiter = new RateLimiter(max, Duration.ofSeconds(10), time);
        List<Long> calls = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            limiter.acquire();
            calls.add(time.now);
            time.now += Duration.ofMillis(300).toNanos();
        }

        for (int i = max; i < calls.size(); i++) {
            assertTrue(calls.get(i) - calls.get(i - max) >= window,
                    "more than " + max + " calls inside one window at call " + i);
        }
    }

    @Test
    void rejectsInvalidLimit() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, Duration.ofSeconds(1)));
    }
}
