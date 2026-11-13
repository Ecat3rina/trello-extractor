package com.ecaterina;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionSchedulerTest {

    @Test
    void runsRepeatedlyAtTheInterval() throws Exception {
        CountDownLatch threeRuns = new CountDownLatch(3);
        try (ExtractionScheduler scheduler = new ExtractionScheduler()) {
            scheduler.start(threeRuns::countDown, Duration.ofMillis(50));
            assertTrue(threeRuns.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void keepsRunningAfterAFailedRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        try (ExtractionScheduler scheduler = new ExtractionScheduler()) {
            scheduler.start(() -> {
                if (runs.incrementAndGet() == 1) {
                    throw new RuntimeException("Trello is down");
                }
                recovered.countDown();
            }, Duration.ofMillis(50));
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void runsNeverOverlap() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);
        try (ExtractionScheduler scheduler = new ExtractionScheduler()) {
            scheduler.start(() -> {
                maxConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(80);            // slower than the 10 ms interval
                running.decrementAndGet();
                done.countDown();
            }, Duration.ofMillis(10));
            assertTrue(done.await(3, TimeUnit.SECONDS));
        }
        assertEquals(1, maxConcurrent.get());
    }

    @Test
    void rejectsNonPositiveInterval() {
        try (ExtractionScheduler scheduler = new ExtractionScheduler()) {
            assertThrows(IllegalArgumentException.class, () -> scheduler.start(() -> { }, Duration.ZERO));
        }
    }
}
