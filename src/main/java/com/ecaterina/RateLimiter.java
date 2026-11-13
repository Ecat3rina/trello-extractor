package com.ecaterina;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window rate limiter: allows at most {@code maxRequests} calls in any
 * {@code window}. Thread-safe, so one instance can be shared by all workers that
 * use the same Trello token.
 *
 * <p>Trello allows 100 requests per 10 seconds per token (300 per 10 s per key).
 * The default used by the app (90 / 10 s) leaves a safety margin.
 */
public class RateLimiter {

    /** Abstraction over time so the limiter can be tested without real sleeping. */
    public interface TimeSource {
        long nanoTime();

        void sleepNanos(long nanos) throws InterruptedException;

        TimeSource SYSTEM = new TimeSource() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
            }
        };
    }

    private final int maxRequests;
    private final long windowNanos;
    private final TimeSource time;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, TimeSource.SYSTEM);
    }

    public RateLimiter(int maxRequests, Duration window, TimeSource time) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be > 0");
        }
        this.maxRequests = maxRequests;
        this.windowNanos = window.toNanos();
        this.time = time;
    }

    /** Blocks until a request is allowed, then records it. */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            long now = time.nanoTime();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowNanos) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                return;
            }
            long waitNanos = windowNanos - (now - timestamps.peekFirst());
            time.sleepNanos(Math.max(waitNanos, 1));
        }
    }
}
