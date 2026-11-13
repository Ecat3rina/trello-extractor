# Trello Extractor

A small Java program that reads **members** and **boards** from the Trello REST API
and writes **each member and each board to its own JSON file**. It runs on a schedule:
every **5 minutes** (default) or every **30 seconds**.

```
output/
├── boards/
│   ├── 6543a1...json
│   └── 6543b2...json
└── members/
    ├── 5f12c3...json
    ├── 5f12d4...json
    └── 5f12e5...json
```

## Requirements

- Java 17+
- Maven 3.8+
- A Trello API key and token (no OAuth)

## Setup

1. **Trello account (Task 0).** Create a Trello account, a Workspace with at least
   3 members and at least 2 boards.
2. **API key and token.** Go to <https://trello.com/power-ups/admin>, create a Power-Up
   for your Workspace, open its **API key** page and copy the key. On the same page,
   click the **Token** link and allow access to get a token.
3. **Workspace id (optional but recommended).** It is the short name in the Workspace
   URL, e.g. `https://trello.com/w/myteam` → `myteam`.
4. **Configure.** Either set environment variables, or copy `trello.properties.example`
   to `trello.properties` and fill it in (this file is git-ignored, so secrets are never
   committed):

| Setting               | Required | Default  | Meaning                                    |
|-----------------------|----------|----------|--------------------------------------------|
| `TRELLO_API_KEY`      | yes      |          | API key                                    |
| `TRELLO_API_TOKEN`    | yes      |          | API token                                  |
| `TRELLO_WORKSPACE_ID` | no       |          | Workspace to extract; if empty, the boards the token's user belongs to are used |
| `TRELLO_OUTPUT_DIR`   | no       | `output` | Folder where JSON files are written        |
| `TRELLO_INTERVAL`     | no       | `5m`     | `5m`, `30s` (any `<n>s`, `<n>m`, `<n>h`)    |

Priority: command-line arguments > environment variables > `trello.properties`.

## Build and run

```bash
mvn package                      # compiles, runs the tests, builds target/trello-extractor.jar

java -jar target/trello-extractor.jar                  # every 5 minutes (option 1)
java -jar target/trello-extractor.jar --interval=30s   # every 30 seconds (option 2)
java -jar target/trello-extractor.jar --once           # one extraction, then exit
```

Other options: `--workspace=<id>`, `--output=<dir>`, `--config=<file.properties>`.
Stop the scheduled mode with `Ctrl+C`.

Run only the tests: `mvn test`.

## How it works

| Class                  | Responsibility |
|------------------------|----------------|
| `App`                  | Entry point; wires everything together, runs once or on a schedule |
| `Config`               | Reads settings from args / env / `trello.properties`; never prints secrets |
| `TrelloClient`         | HTTP calls to `https://api.trello.com/1` with key + token; retries on 429/5xx |
| `RateLimiter`          | Thread-safe sliding-window limiter shared by every request |
| `TrelloExtractor`      | Fetches boards and members, writes one JSON per entity, removes deleted ones |
| `JsonFileWriter`       | Atomic writes to `<output>/<boards|members>/<id>.json`, skips unchanged files |
| `ExtractionScheduler`  | Runs the extraction with a fixed delay; a failed run does not stop the schedule |

### Endpoints used

| Mode                      | Boards                                   | Members                                   |
|---------------------------|------------------------------------------|-------------------------------------------|
| With `TRELLO_WORKSPACE_ID`| `GET /organizations/{id}/boards`         | `GET /organizations/{id}/members`         |
| Without                   | `GET /members/me/boards`                 | `GET /boards/{id}/members` for each board, de-duplicated |

### Rate limits

Trello allows **100 requests per 10 seconds per token** (300 per 10 s per key).
The extractor respects this in two layers:

1. **Client-side limiter** — every request first calls `RateLimiter.acquire()`, which
   allows at most **90 requests per 10 s** (a safety margin below 100). The limiter is
   shared by all threads, so parallel requests can never exceed it together.
2. **Server feedback** — if Trello still answers `429 Too Many Requests` (for example
   because another program uses the same token), the request is retried with
   exponential backoff (1 s, 2 s, 4 s, …), using the `Retry-After` header when Trello
   sends one. `5xx` errors and network errors are retried the same way. `401`/`404`
   fail immediately, because retrying would not help.

## Thinking about scale (thousands of boards and employees)

What the solution already does:

- **Few API calls.** In Workspace mode an extraction costs **2 requests in total**,
  whether the Workspace has 10 or 10,000 boards, instead of one request per board or
  per member. This is the main reason the 30-second option is safe.
- **Bounded parallelism under one limiter.** When per-board calls are needed (no
  Workspace configured), they run on a small thread pool (4 threads); the shared rate
  limiter keeps the total within Trello's limits regardless of the thread count.
- **Runs never overlap.** The scheduler uses a *fixed delay* (the next run starts N
  seconds after the previous one ends), so a slow run with thousands of boards cannot
  pile up concurrent runs that would compete for the rate limit.
- **Incremental output.** A file is rewritten only when its content changed, and files
  for deleted boards/members are removed. Downstream consumers can watch the folder
  and only see real changes; disk I/O stays low at 30-second intervals.
- **Atomic writes.** Each file is written to a temp file and moved into place, so a
  reader never sees a half-written JSON.
- **Resilience.** A failed run (network down, Trello outage) is logged and the next
  scheduled run tries again.

What I would add for a much bigger setup:

- **Webhooks instead of polling.** Trello can call a webhook when a board changes.
  Polling every 30 s across thousands of boards wastes most requests on unchanged data;
  webhooks plus a slow full resync (e.g. hourly) give fresher data with far fewer calls.
- **Incremental fetching.** Use `dateLastActivity` on boards to fetch details only for
  boards that changed since the last run.
- **Sharding across tokens/Workspaces.** The limit is per token, so several Workspaces
  can be split across workers, each with its own token and its own limiter. With a
  shared limiter store (e.g. Redis) several instances could share one token safely.
- **Distributed scheduling.** Run as a container/Kubernetes CronJob or behind a lock
  (e.g. ShedLock) so only one instance extracts at a time.
- **Better storage.** For very large volumes, write to object storage (S3) or a
  database / message queue instead of a local folder, and stream large API responses
  instead of holding them in memory.
- **Monitoring.** Metrics for run duration, number of 429s and entities processed.

## Tests (Task 3)

JUnit 5. The HTTP tests run against an in-process fake Trello server
(`FakeTrelloServer`, built on the JDK's `HttpServer`), so they need no network
access and no real credentials.

| Test class                  | What it checks |
|-----------------------------|----------------|
| `RateLimiterTest`           | No waiting below the limit; waits exactly until the window frees up; never more than N calls in any window (uses a fake clock, so it is fast and deterministic) |
| `TrelloClientTest`          | Key and token are sent; JSON is parsed; retries on 429 and 5xx; gives up after max attempts; no retry on 401; token never appears in errors |
| `JsonFileWriterTest`        | One file per entity; created/updated/unchanged detection; no temp files left; pruning of deleted entities; rejects unsafe ids (path traversal) |
| `TrelloExtractorTest`       | End-to-end: 3 members + 2 boards → 5 files with 2 API calls; de-duplication of members across boards; second run writes nothing; deleted boards are removed; survives a 429 mid-run |
| `ConfigTest`                | `5m` / `30s` parsing, defaults, priority of args over env, properties file, secrets hidden |
| `ExtractionSchedulerTest`   | Runs repeatedly; keeps going after a failure; runs never overlap |
| `AppTest`                   | Rate limit below Trello's limit; wiring from config |
