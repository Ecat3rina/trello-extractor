package com.ecaterina;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a job periodically with a fixed delay between the end of one run and the start
 * of the next. Runs therefore never overlap, even when one extraction takes longer than
 * the interval (important at scale). A failed run is logged and the schedule continues.
 */
public class ExtractionScheduler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ExtractionScheduler.class.getName());

    /** A unit of work that may throw. */
    @FunctionalInterface
    public interface Job {
        void run() throws Exception;
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "trello-extractor");
        t.setDaemon(false);
        return t;
    });

    public ScheduledFuture<?> start(Job job, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Interval must be positive");
        }
        LOG.info("Scheduling extraction every " + interval.toSeconds() + " s");
        return executor.scheduleWithFixedDelay(() -> {
            try {
                job.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Extraction run failed, will retry at next interval", e);
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
