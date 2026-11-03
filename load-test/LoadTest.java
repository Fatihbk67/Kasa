import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives a running server with concurrent authorize-capture flows, then checks that no money went missing.
 * Needs only a JDK: java LoadTest.java [baseUrl] [workers] [seconds] [merchants] [adminToken]
 */
public class LoadTest {

    private static final long FEE_BASIS_POINTS = 250;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private record Merchant(String apiKey, String token, AtomicLong expectedBalance) {
    }

    private record Reply(int status, String body) {
    }

    private static String baseUrl;
    private static final Map<String, List<Long>> LATENCIES = new ConcurrentHashMap<>();
    private static final AtomicLong UNEXPECTED = new AtomicLong();

    public static void main(String[] args) throws Exception {
        baseUrl = arg(args, 0, "http://localhost:8080");
        int workers = Integer.parseInt(arg(args, 1, "50"));
        int seconds = Integer.parseInt(arg(args, 2, "30"));
        int merchantCount = Integer.parseInt(arg(args, 3, "5"));
        String adminToken = arg(args, 4, "local-dev-admin-token");

        List<Merchant> merchants = new ArrayList<>();
        for (int i = 0; i < merchantCount; i++) {
            merchants.add(newMerchant(adminToken));
        }

        Instant deadline = Instant.now().plusSeconds(seconds);
        AtomicLong completedFlows = new AtomicLong();
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < workers; worker++) {
                Merchant merchant = merchants.get(worker % merchantCount);
                pool.submit(() -> {
                    while (Instant.now().isBefore(deadline)) {
                        runFlow(merchant, completedFlows);
                    }
                });
            }
        }
        double elapsedSeconds = (System.nanoTime() - started) / 1e9;

        long mismatched = verifyBalances(merchants);
        String reconciliation = reconcile(adminToken);

        System.out.printf(Locale.ROOT, "%nworkers=%d merchants=%d duration=%.1fs%n", workers, merchantCount, elapsedSeconds);
        System.out.printf(Locale.ROOT, "completed flows=%d (%.1f/s), http requests=%.1f/s%n", completedFlows.get(),
                completedFlows.get() / elapsedSeconds, totalRequests() / elapsedSeconds);
        System.out.printf(Locale.ROOT, "%-12s %8s %8s %8s %8s%n", "operation", "count", "p50 ms", "p95 ms", "p99 ms");
        for (String name : List.of("authorize", "capture", "read")) {
            List<Long> values = new ArrayList<>(LATENCIES.getOrDefault(name, List.of()));
            Collections.sort(values);
            System.out.printf(Locale.ROOT, "%-12s %8d %8.1f %8.1f %8.1f%n", name, values.size(),
                    percentile(values, 0.50), percentile(values, 0.95), percentile(values, 0.99));
        }
        System.out.printf("unexpected responses=%d%n", UNEXPECTED.get());
        System.out.printf("merchants with wrong balance=%d of %d%n", mismatched, merchantCount);
        System.out.printf("reconciliation: %s%n", reconciliation);
    }

    private static void runFlow(Merchant merchant, AtomicLong completedFlows) {
        try {
            long amount = 1_000 + (long) (Math.random() * 100_000);
            Reply created = timed("authorize", () -> post("/api/v1/payments", merchant,
                    "{\"amount\":" + amount + ",\"currency\":\"TRY\",\"cardToken\":\"tok_success\"}"));
            if (created.status() != 201) {
                UNEXPECTED.incrementAndGet();
                return;
            }
            String id = find(created.body(), "\"payment\":\\{\"id\":\"([^\"]+)\"");
            Reply captured = timed("capture", () -> post("/api/v1/payments/" + id + "/capture", merchant, null));
            if (captured.status() != 200) {
                UNEXPECTED.incrementAndGet();
                return;
            }
            merchant.expectedBalance().addAndGet(amount - (amount * FEE_BASIS_POINTS + 5_000) / 10_000);
            Reply read = timed("read", () -> get("/api/v1/payments/" + id, merchant.apiKey()));
            if (read.status() != 200 || !read.body().contains("\"status\":\"CAPTURED\"")) {
                UNEXPECTED.incrementAndGet();
                return;
            }
            completedFlows.incrementAndGet();
        } catch (Exception e) {
            UNEXPECTED.incrementAndGet();
        }
    }

    private static long verifyBalances(List<Merchant> merchants) throws Exception {
        long mismatched = 0;
        for (Merchant merchant : merchants) {
            Reply balances = get("/api/v1/balances", merchant.apiKey());
            long actual = Long.parseLong(find(balances.body(), "\"available\":(-?\\d+)"));
            if (actual != merchant.expectedBalance().get()) {
                mismatched++;
                System.out.printf("expected %d but balance is %d%n", merchant.expectedBalance().get(), actual);
            }
        }
        return mismatched;
    }

    private static String reconcile(String adminToken) throws Exception {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/admin/reconciliation/runs?date=" + date))
                .header("X-Admin-Token", adminToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        String body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
        return "status=" + find(body, "\"status\":\"([A-Z]+)\"") + " bankLines=" + find(body, "\"bankLineCount\":(\\d+)")
                + " matched=" + find(body, "\"matchedCount\":(\\d+)") + " discrepancies=" + find(body, "\"discrepancyCount\":(\\d+)");
    }

    /** New merchants wait for review, so each one is approved before it takes its first payment. */
    private static Merchant newMerchant(String adminToken) throws Exception {
        String email = "load-" + UUID.randomUUID() + "@example.com";
        Reply registered = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"businessName\":\"Load\",\"email\":\"" + email
                        + "\",\"password\":\"load-test-password-1\"}")));
        String token = find(registered.body(), "\"accessToken\":\"([^\"]+)\"");
        String tenantId = find(registered.body(), "\"tenantId\":\"([^\"]+)\"");
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/admin/merchants/" + tenantId + "/approve"))
                .header("X-Admin-Token", adminToken)
                .POST(HttpRequest.BodyPublishers.noBody()));
        Reply key = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/api-keys"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"load\"}")));
        return new Merchant(find(key.body(), "\"key\":\"([^\"]+)\""), token, new AtomicLong());
    }

    private interface Call {
        Reply run() throws Exception;
    }

    private static Reply timed(String name, Call call) throws Exception {
        long start = System.nanoTime();
        Reply reply = call.run();
        LATENCIES.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(System.nanoTime() - start);
        return reply;
    }

    private static Reply post(String path, Merchant merchant, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Api-Key", merchant.apiKey())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (json == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        }
        return send(request);
    }

    private static Reply get(String path, String apiKey) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("X-Api-Key", apiKey));
    }

    private static Reply send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private static String find(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("no match for " + regex + " in " + text);
        }
        return matcher.group(1);
    }

    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1)) / 1e6;
    }

    private static long totalRequests() {
        return LATENCIES.values().stream().mapToLong(List::size).sum();
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }
}
