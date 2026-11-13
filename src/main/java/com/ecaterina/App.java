package com.ecaterina;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Entry point.
 *
 * <pre>
 *   java -jar target/trello-extractor.jar                 # every 5 minutes (default)
 *   java -jar target/trello-extractor.jar --interval=30s  # every 30 seconds
 *   java -jar target/trello-extractor.jar --once          # single run, then exit
 * </pre>
 */
public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    /** Trello: 100 requests / 10 s per token. Stay a little below it. */
    static final int REQUESTS_PER_WINDOW = 90;
    static final Duration RATE_WINDOW = Duration.ofSeconds(10);
    static final int PARALLELISM = 4;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");

        Config config;
        try {
            config = Config.load(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }
        LOG.info("Starting with " + config);

        TrelloExtractor extractor = createExtractor(config);

        if (config.once()) {
            extractor.extract();
            return;
        }

        CountDownLatch stop = new CountDownLatch(1);
        try (ExtractionScheduler scheduler = new ExtractionScheduler()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down");
                stop.countDown();
            }));
            scheduler.start(extractor::extract, config.interval());
            stop.await();
        }
    }

    static TrelloExtractor createExtractor(Config config) {
        RateLimiter limiter = new RateLimiter(REQUESTS_PER_WINDOW, RATE_WINDOW);
        TrelloClient client = new TrelloClient(config.baseUrl(), config.apiKey(), config.apiToken(), limiter);
        JsonFileWriter writer = new JsonFileWriter(config.outputDir());
        return new TrelloExtractor(client, writer, config.workspaceId(), PARALLELISM);
    }
}
