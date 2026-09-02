package dev.aurum.benchmark;

import dev.aurum.account.AccountService;
import dev.aurum.account.AccountView;
import dev.aurum.ledger.LedgerService;
import dev.aurum.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HttpLoadBenchmark {

    private static final int OPERATIONS = Integer.getInteger("aurum.load.operations", 500);
    private static final int CONCURRENCY = Integer.getInteger("aurum.load.concurrency", 8);
    private static final int PAIRS = Integer.getInteger("aurum.load.account-pairs", 8);
    private static final String CUSTOMER_AUTH = "Basic " + Base64.getEncoder().encodeToString(
            "customer:customer-local".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aurum_http_load")
            .withUsername("aurum")
            .withPassword("aurum");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("aurum.reconciliation.schedule.enabled", () -> false);
        registry.add("debug", () -> false);
        registry.add("logging.level.root", () -> "WARN");
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    @Autowired
    LedgerService ledger;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void measureDistributedAndHotAccountTraffic() throws Exception {
        if (OPERATIONS < 1 || CONCURRENCY < 1 || PAIRS < 2) {
            throw new IllegalArgumentException("operations/concurrency must be positive and account-pairs at least 2");
        }

        List<AccountView> distributed = new ArrayList<>();
        for (int index = 0; index < PAIRS * 2; index++) {
            AccountView account = accounts.create("Load distributed " + index, "INR");
            ledger.fund(account.id(), OPERATIONS * 10L, "INR", "load seed", key());
            distributed.add(account);
        }
        LoadResult normal = execute("distributed", OPERATIONS, operation -> {
            int pair = operation % PAIRS;
            return request(distributed.get(pair).id(), distributed.get(PAIRS + pair).id(), operation);
        });

        AccountView hotSource = accounts.create("Load hot source", "INR");
        AccountView hotDestination = accounts.create("Load hot destination", "INR");
        ledger.fund(hotSource.id(), OPERATIONS * 10L, "INR", "load seed", key());
        LoadResult hot = execute("hot-account", OPERATIONS,
                operation -> request(hotSource.id(), hotDestination.id(), operation));

        assertThat(normal.errors()).isZero();
        assertThat(hot.errors()).isZero();
        assertThat(reconciliation.reconcile().consistent()).isTrue();
        writeReport(normal, hot);
    }

    private LoadResult execute(String name, int operations, RequestFactory requests) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(operations));
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long wallStart = System.nanoTime();

        try {
            for (int operation = 0; operation < operations; operation++) {
                int current = operation;
                futures.add(executor.submit(() -> {
                    start.await();
                    HttpRequest request = requests.create(current);
                    long started = System.nanoTime();
                    try {
                        HttpResponse<Void> response = client.send(
                                request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() != 201) {
                            errors.incrementAndGet();
                        }
                    } catch (Exception exception) {
                        errors.incrementAndGet();
                    } finally {
                        latencies.add(System.nanoTime() - started);
                    }
                    return null;
                }));
            }
            wallStart = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        long elapsed = System.nanoTime() - wallStart;
        List<Long> ordered = latencies.stream().sorted().toList();
        return new LoadResult(name, operations, CONCURRENCY, errors.get(),
                operations / (elapsed / 1_000_000_000.0),
                millis(percentile(ordered, 0.50)),
                millis(percentile(ordered, 0.95)),
                millis(percentile(ordered, 0.99)));
    }

    private HttpRequest request(UUID source, UUID destination, int operation) {
        String body = """
                {"sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinor":1,"currency":"INR","reference":"http load"}
                """.formatted(source, destination);
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", CUSTOMER_AUTH)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "load-" + source + "-" + operation + "-" + key())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private long percentile(List<Long> ordered, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * ordered.size()) - 1);
        return ordered.get(index);
    }

    private double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private void writeReport(LoadResult normal, LoadResult hot) throws Exception {
        Path report = Path.of("target", "benchmarks", "http-load.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                # Aurum HTTP load report

                Generated at `%s` with Java `%s`, %,d operations per workload and %,d account pairs.

                | Workload | Concurrency | Throughput | p50 | p95 | p99 | Errors |
                |---|---:|---:|---:|---:|---:|---:|
                | Distributed | %,d | %.1f tx/s | %.3f ms | %.3f ms | %.3f ms | %,d |
                | Hot account | %,d | %.1f tx/s | %.3f ms | %.3f ms | %.3f ms | %,d |

                Results include HTTP Basic authentication, JSON handling, application logic and PostgreSQL commit
                latency on this development machine. They are comparative evidence, not a production SLO.
                """.formatted(
                Instant.now(), System.getProperty("java.version"), OPERATIONS, PAIRS,
                normal.concurrency(), normal.throughput(), normal.p50Millis(), normal.p95Millis(),
                normal.p99Millis(), normal.errors(), hot.concurrency(), hot.throughput(),
                hot.p50Millis(), hot.p95Millis(), hot.p99Millis(), hot.errors()));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest create(int operation);
    }

    private record LoadResult(
            String name,
            int operations,
            int concurrency,
            int errors,
            double throughput,
            double p50Millis,
            double p95Millis,
            double p99Millis
    ) {
    }
}
