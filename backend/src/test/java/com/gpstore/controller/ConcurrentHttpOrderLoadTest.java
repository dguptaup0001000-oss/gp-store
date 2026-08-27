package com.gpstore.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.Address;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.Role;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.gpstore.service.JwtService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP 10 / 25 / 50 / 100 concurrent COD checkouts through Tomcat.
 *
 * Previous ConcurrentOrderLoadTest called OrderService in-process. That does
 * not prove HTTP performance. This class starts a random-port server and
 * drives catalog GET, product GET, cart add, checkout preview, and COD
 * placeOrder over HTTP. It refuses to be pointed at production: the server
 * is the in-process test JVM.
 *
 * Pool/thread ceilings match production defaults (20 / 80). Rate limits are
 * raised so this measures checkout integrity under concurrency, not the
 * anti-abuse limiter (covered elsewhere).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "spring.datasource.hikari.minimum-idle=4",
        "spring.datasource.hikari.connection-timeout=60000",
        "server.tomcat.threads.max=80",
        "server.tomcat.accept-count=120",
        "rate-limit.auth-per-minute=250",
        "rate-limit.checkout-per-minute=250",
        "rate-limit.mutation-per-minute=250",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class ConcurrentHttpOrderLoadTest {

    private static final List<Map<String, Object>> WAVES = new CopyOnWriteArrayList<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwtService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Value("${store.latitude}") private double storeLatitude;
    @Value("${store.longitude}") private double storeLongitude;

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/v1" + path;
    }

    @Test
    @Timeout(value = 180)
    @DisplayName("HTTP 10 concurrent catalog+COD checkouts")
    void tenConcurrentHttp() throws Exception {
        runWave(10);
    }

    @Test
    @Timeout(value = 180)
    @DisplayName("HTTP 25 concurrent catalog+COD checkouts")
    void twentyFiveConcurrentHttp() throws Exception {
        runWave(25);
    }

    @Test
    @Timeout(value = 240)
    @DisplayName("HTTP 50 concurrent catalog+COD checkouts")
    void fiftyConcurrentHttp() throws Exception {
        runWave(50);
    }

    @Test
    @Timeout(value = 300)
    @DisplayName("HTTP 100 concurrent catalog+COD checkouts")
    void oneHundredConcurrentHttp() throws Exception {
        runWave(100);
    }

    @Test
    @Timeout(value = 180)
    @DisplayName("HTTP concurrent same Idempotency-Key creates one order")
    void concurrentHttpIdempotency() throws Exception {
        ProductVariant variant = createVariant();
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(50);
        inventoryRepository.save(inventory);

        Buyer buyer = newBuyer(0);
        HttpHeaders cartHeaders = bearer(buyer.token);
        ResponseEntity<String> added = rest.exchange(
                url("/api/carts/add?variantId=" + variant.getId() + "&quantity=1"),
                HttpMethod.POST, new HttpEntity<>(cartHeaders), String.class);
        assertEquals(200, added.getStatusCode().value(), "seed cart: " + added.getBody());

        String key = "http-idem-" + UUID.randomUUID();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<Long> orderIds = new CopyOnWriteArrayList<>();
        AtomicInteger httpErrors = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    HttpHeaders h = bearer(buyer.token);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", key);
                    HttpEntity<String> entity = new HttpEntity<>(
                            "{\"addressId\":" + buyer.addressId + ",\"paymentMethod\":\"COD\"}", h);
                    ready.countDown();
                    go.await();
                    ResponseEntity<String> res = rest.exchange(
                            url("/api/orders/place"), HttpMethod.POST, entity, String.class);
                    if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                        JsonNode node = MAPPER.readTree(res.getBody());
                        if (node.path("success").asBoolean(false) && node.hasNonNull("orderId")) {
                            orderIds.add(node.get("orderId").asLong());
                        }
                    } else if (res.getStatusCode().value() >= 500) {
                        httpErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    httpErrors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "idempotency wave did not finish");
        pool.shutdown();

        assertEquals(0, httpErrors.get(), "idempotency wave must not 5xx");
        Set<Long> unique = new HashSet<>(orderIds);
        assertEquals(1, unique.size(), "duplicate orders from the same Idempotency-Key: " + unique);

        long stored = orderRepository.findAll().stream()
                .filter(o -> o.getCustomer() != null && o.getCustomer().getId().equals(buyer.customerId))
                .count();
        assertEquals(1, stored, "database must hold exactly one order for the idempotent checkout");
    }

    private void runWave(int n) throws Exception {
        ResponseEntity<String> health = rest.getForEntity(url("/api/health"), String.class);
        assertEquals(200, health.getStatusCode().value(), health.getBody());
        ResponseEntity<String> readyProbe = rest.getForEntity(url("/api/health/ready"), String.class);
        assertEquals(200, readyProbe.getStatusCode().value(), readyProbe.getBody());
        assertTrue(readyProbe.getBody() != null && readyProbe.getBody().contains("ready"));

        ProductVariant variant = createVariant();
        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(n + 50);
        inventory = inventoryRepository.save(inventory);
        int startingStock = inventory.getStock();
        long productId = variant.getProduct().getId();
        long variantId = variant.getId();

        List<Buyer> buyers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            buyers.add(newBuyer(i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<Long> okOrderIds = new CopyOnWriteArrayList<>();
        List<Long> allHttpMs = new CopyOnWriteArrayList<>();
        List<Long> placeMs = new CopyOnWriteArrayList<>();
        List<Long> catalogMs = new CopyOnWriteArrayList<>();
        AtomicInteger httpErrors = new AtomicInteger();
        AtomicInteger catalogShed = new AtomicInteger();
        AtomicInteger placeRejected = new AtomicInteger();
        List<String> failures = new CopyOnWriteArrayList<>();

        ResourceSampler sampler = new ResourceSampler(dataSource, jdbc);
        sampler.start();
        long startedNanos = System.nanoTime();

        for (Buyer buyer : buyers) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();

                    timed(allHttpMs, catalogMs, () -> {
                        ResponseEntity<String> feed = rest.getForEntity(url("/api/products/feed?page=0&size=20"), String.class);
                        recordHttp(feed, httpErrors, catalogShed, failures, "feed", true);
                    });
                    timed(allHttpMs, null, () -> {
                        ResponseEntity<String> details = rest.getForEntity(url("/api/products/" + productId), String.class);
                        recordHttp(details, httpErrors, catalogShed, failures, "details", true);
                    });
                    timed(allHttpMs, null, () -> {
                        ResponseEntity<String> cart = rest.exchange(
                                url("/api/carts/add?variantId=" + variantId + "&quantity=1"),
                                HttpMethod.POST, new HttpEntity<>(bearer(buyer.token)), String.class);
                        recordHttp(cart, httpErrors, catalogShed, failures, "cart", false);
                    });
                    timed(allHttpMs, null, () -> {
                        ResponseEntity<String> preview = rest.exchange(
                                url("/api/orders/checkout-preview?addressId=" + buyer.addressId),
                                HttpMethod.GET, new HttpEntity<>(bearer(buyer.token)), String.class);
                        recordHttp(preview, httpErrors, catalogShed, failures, "preview", false);
                    });

                    HttpHeaders h = bearer(buyer.token);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("Idempotency-Key", "http-load-" + n + "-" + buyer.customerId + "-" + UUID.randomUUID());
                    HttpEntity<String> placeEntity = new HttpEntity<>(
                            "{\"addressId\":" + buyer.addressId + ",\"paymentMethod\":\"COD\"}", h);
                    long t0 = System.nanoTime();
                    ResponseEntity<String> placed = rest.exchange(
                            url("/api/orders/place"), HttpMethod.POST, placeEntity, String.class);
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    allHttpMs.add(ms);
                    placeMs.add(ms);
                    if (!placed.getStatusCode().is2xxSuccessful()) {
                        httpErrors.incrementAndGet();
                        placeRejected.incrementAndGet();
                        failures.add("place HTTP " + placed.getStatusCode() + " " + placed.getBody());
                        return;
                    }
                    JsonNode node = MAPPER.readTree(placed.getBody() == null ? "{}" : placed.getBody());
                    if (node.path("success").asBoolean(false) && node.hasNonNull("orderId")) {
                        okOrderIds.add(node.get("orderId").asLong());
                    } else {
                        placeRejected.incrementAndGet();
                        failures.add("place success=false " + placed.getBody());
                    }
                } catch (Exception e) {
                    httpErrors.incrementAndGet();
                    failures.add(e.toString());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(60, TimeUnit.SECONDS), "buyers failed to arm");
        go.countDown();
        assertTrue(done.await(240, TimeUnit.SECONDS), n + " HTTP checkouts did not finish");
        pool.shutdown();
        long elapsedMs = Math.max(1L, (System.nanoTime() - startedNanos) / 1_000_000L);
        sampler.stop();

        assertEquals(0, httpErrors.get(),
                "checkout HTTP errors must be 0 (catalog 503 shed is counted separately): "
                        + failures.stream().limit(8).toList());
        assertEquals(0, placeRejected.get(), "every buyer with stock must succeed");
        assertEquals(n, okOrderIds.size(), "lost orders: expected " + n + " got " + okOrderIds.size());
        assertEquals(n, new HashSet<>(okOrderIds).size(), "duplicate order ids in HTTP responses");

        List<Long> buyerIds = buyers.stream().map(b -> b.customerId).toList();
        List<Order> stored = orderRepository.findAll().stream()
                .filter(o -> o.getCustomer() != null && buyerIds.contains(o.getCustomer().getId()))
                .toList();
        assertEquals(n, stored.size(), "database order count must match successful HTTP checkouts");

        int payments = 0;
        int mismatches = 0;
        for (Order order : stored) {
            var row = paymentRepository.findByOrderId(order.getId());
            if (row.isEmpty()) {
                mismatches++;
                continue;
            }
            payments++;
            Payment payment = row.get();
            if (payment.getAmount() == null
                    || payment.getAmount().compareTo(order.getTotalAmount()) != 0) {
                mismatches++;
            }
        }
        assertEquals(n, payments, "each order must have exactly one payment row");
        assertEquals(0, mismatches, "payment/order mismatches");

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();
        assertTrue(after.getStock() >= 0, "stock must never go negative");
        assertEquals(startingStock - n, after.getStock(),
                "stock must drop by exactly the number of successful orders");

        int totalHttp = allHttpMs.size();
        double rps = totalHttp * 1000.0 / elapsedMs;
        Map<String, Object> wave = new LinkedHashMap<>();
        wave.put("concurrency", n);
        wave.put("elapsed_ms", elapsedMs);
        wave.put("requests", totalHttp);
        wave.put("requests_per_sec", round1(rps));
        wave.put("success_rate", 1.0);
        wave.put("http_error_rate", round3(httpErrors.get() / (double) Math.max(1, totalHttp)));
        wave.put("catalog_shed_503", catalogShed.get());
        wave.put("place_ok", okOrderIds.size());
        wave.put("duplicate_orders", 0);
        wave.put("lost_orders", 0);
        wave.put("negative_inventory", 0);
        wave.put("payment_mismatch", 0);
        wave.put("p50_ms_all", percentile(allHttpMs, 50));
        wave.put("p95_ms_all", percentile(allHttpMs, 95));
        wave.put("p99_ms_all", percentile(allHttpMs, 99));
        wave.put("p50_ms_place", percentile(placeMs, 50));
        wave.put("p95_ms_place", percentile(placeMs, 95));
        wave.put("p99_ms_place", percentile(placeMs, 99));
        wave.put("p50_ms_catalog", percentile(catalogMs, 50));
        wave.put("p95_ms_catalog", percentile(catalogMs, 95));
        wave.put("peak_cpu_process", sampler.peakCpu);
        wave.put("peak_heap_bytes", sampler.peakHeap);
        wave.put("peak_hikari_active", sampler.peakHikariActive);
        wave.put("peak_hikari_waiting", sampler.peakHikariWaiting);
        wave.put("hikari_pool_max", sampler.hikariMax);
        wave.put("peak_hikari_utilization", sampler.hikariMax == 0 ? 0.0
                : round3(sampler.peakHikariActive / (double) sampler.hikariMax));
        wave.put("peak_pg_stat_activity", sampler.peakPg);
        wave.put("environment", "isolated-springboottest-random-port");
        WAVES.add(wave);
        writeReport();

        System.out.println("HTTP_CONCURRENT_WAVE " + MAPPER.writeValueAsString(wave));
    }

    @AfterAll
    static void printDegradation() throws Exception {
        if (WAVES.isEmpty()) {
            return;
        }
        System.out.println("HTTP_CONCURRENT_DEGRADATION " + MAPPER.writeValueAsString(WAVES));
    }

    private static void writeReport() {
        try {
            File dir = new File("target");
            if (!dir.exists()) {
                Files.createDirectories(dir.toPath());
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("waves", WAVES);
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(dir, "http-concurrent-load-report.json"), report);
        } catch (Exception e) {
            System.out.println("Could not write HTTP load report: " + e.getMessage());
        }
    }

    private static void timed(List<Long> all, List<Long> extra, Runnable action) {
        long t0 = System.nanoTime();
        action.run();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        all.add(ms);
        if (extra != null) {
            extra.add(ms);
        }
    }

    private static void recordHttp(ResponseEntity<String> res, AtomicInteger httpErrors,
                                   AtomicInteger catalogShed, List<String> failures,
                                   String name, boolean catalogGet) {
        if (res.getStatusCode().is2xxSuccessful()) {
            return;
        }
        if (catalogGet && res.getStatusCode().value() == 503) {
            catalogShed.incrementAndGet();
            return;
        }
        httpErrors.incrementAndGet();
        failures.add(name + " HTTP " + res.getStatusCode() + " " + res.getBody());
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private Buyer newBuyer(int index) {
        Customer customer = new Customer();
        customer.setFullName("HTTP Load Buyer");
        customer.setEmail("http-load-" + System.nanoTime() + "-" + index + "-" + UUID.randomUUID() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", Math.abs((System.nanoTime() + index) % 1_000_000_000L)));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setRole(Role.CUSTOMER);
        customer = customerRepository.save(customer);

        Address address = new Address();
        address.setCustomer(customer);
        address.setFullName(customer.getFullName());
        address.setMobileNumber(customer.getMobileNumber());
        address.setHouseNo("1");
        address.setArea("Test Area");
        address.setCity("Test City");
        address.setState("Test State");
        address.setPincode("110001");
        address.setCountry("India");
        address.setLatitude(storeLatitude);
        address.setLongitude(storeLongitude);
        address.setDefaultAddress(true);
        address = addressRepository.save(address);

        String token = jwtService.generateToken(customer.getId(), customer.getEmail(), Role.CUSTOMER);
        return new Buyer(customer.getId(), address.getId(), token);
    }

    private ProductVariant createVariant() {
        Category category = new Category();
        category.setName("HTTP Load Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("HTTP Load Item " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100.00"));
        variant.setSellingPrice(new BigDecimal("90.00"));
        variant.setAvailable(true);
        variant.setActive(true);
        return productVariantRepository.save(variant);
    }

    private static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.min(sorted.size() - 1, Math.max(0, idx));
        return sorted.get(idx);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record Buyer(long customerId, long addressId, String token) {}

    private static final class ResourceSampler {
        private final DataSource dataSource;
        private final JdbcTemplate jdbc;
        private final AtomicBoolean stop = new AtomicBoolean();
        private Thread thread;
        volatile double peakCpu;
        volatile long peakHeap;
        volatile int peakHikariActive;
        volatile int peakHikariWaiting;
        volatile int peakPg;
        volatile int hikariMax;
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final AtomicReference<com.sun.management.OperatingSystemMXBean> os =
                new AtomicReference<>();

        ResourceSampler(DataSource dataSource, JdbcTemplate jdbc) {
            this.dataSource = dataSource;
            this.jdbc = jdbc;
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean bean) {
                os.set(bean);
            }
        }

        void start() {
            sample();
            thread = new Thread(() -> {
                while (!stop.get()) {
                    sample();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "http-load-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stop.set(true);
            if (thread != null) {
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sample();
        }

        private void sample() {
            try {
                com.sun.management.OperatingSystemMXBean bean = os.get();
                if (bean != null) {
                    double cpu = bean.getProcessCpuLoad();
                    if (cpu >= 0 && cpu > peakCpu) {
                        peakCpu = cpu;
                    }
                }
                long heap = memory.getHeapMemoryUsage().getUsed();
                if (heap > peakHeap) {
                    peakHeap = heap;
                }
                HikariDataSource hikari = unwrap();
                if (hikari != null) {
                    hikariMax = hikari.getMaximumPoolSize();
                    HikariPoolMXBean mx = hikari.getHikariPoolMXBean();
                    if (mx != null) {
                        int active = mx.getActiveConnections();
                        int waiting = mx.getThreadsAwaitingConnection();
                        if (active > peakHikariActive) {
                            peakHikariActive = active;
                        }
                        if (waiting > peakHikariWaiting) {
                            peakHikariWaiting = waiting;
                        }
                    }
                }
                Integer pg = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity", Integer.class);
                if (pg != null && pg > peakPg) {
                    peakPg = pg;
                }
            } catch (RuntimeException ignored) {
                // Sampling must not fail the load test.
            }
        }

        private HikariDataSource unwrap() {
            if (dataSource instanceof HikariDataSource hikari) {
                return hikari;
            }
            try {
                return dataSource.unwrap(HikariDataSource.class);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
