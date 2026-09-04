package com.gpstore.security;

import com.gpstore.entity.Product;
import com.gpstore.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Creating a product must not be able to overwrite a different product.
 *
 * THE SHAPE, and this codebase already knows it. ProductController binds the
 * raw entity:
 *
 *     public ProductResponse createProduct(@RequestBody Product product)
 *
 * and ProductService.saveProduct calls productRepository.save(product) with no
 * id guard. JPA save() on an entity that already carries an id is an UPDATE,
 * not an insert. So POST /api/products with {"id": 42, ...} does not create
 * anything - it rewrites product 42, and answers 200 as though a create had
 * succeeded.
 *
 * CustomerService line 116 already guards exactly this, with a comment that
 * says why: "save() on an entity carrying an id rewrites that row, so an id
 * arriving from anywhere would turn a create into an overwrite of whichever
 * account it named." CreateCustomerCannotEscalateTest pins it for customers.
 * Products never got the same guard.
 *
 * WHAT IT COSTS. Not a privilege escalation - POST /api/products already needs
 * CATALOG_MANAGE. It is silent catalogue corruption: a shopkeeper adding a new
 * item can overwrite the name, brand and active flag of an unrelated product,
 * see "success", and find out when a customer orders the wrong thing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rate-limit.auth-per-minute=250",
        "rate-limit.mutation-per-minute=250",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Creating a product cannot overwrite another product")
class CreateProductCannotOverwriteAnotherTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductRepository products;

    private String url(String path) {
        return "http://localhost:" + port + "/v1" + path;
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private HttpEntity<String> bearer(String token, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private static String phone() {
        return "9" + (100000000 + (int) (Math.random() * 899999999));
    }

    @SuppressWarnings("rawtypes")
    private String adminToken() {
        String email = "prod-admin-" + System.nanoTime() + "@example.com";
        ResponseEntity<java.util.Map> res = rest.postForEntity(
                url("/api/auth/register"),
                json("""
                     {"name":"Cat Admin","email":"%s","phone":"%s","password":"Passw0rd!23"}
                     """.formatted(email, phone())),
                java.util.Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "registration failed: " + res.getBody());
        jdbc.update("UPDATE customers SET role = 'ADMIN' WHERE email = ?", email);
        ResponseEntity<java.util.Map> login = rest.postForEntity(
                url("/api/auth/login"),
                json("""
                     {"email":"%s","password":"Passw0rd!23"}
                     """.formatted(email)),
                java.util.Map.class);
        return (String) login.getBody().get("token");
    }

    private Product existingProduct() {
        Product p = new Product();
        p.setName("Tata Salt 1kg");
        p.setBrand("Tata");
        p.setActive(true);
        return products.save(p);
    }

    @Test
    @DisplayName("an id in the create body does not rewrite that product")
    void idInTheBodyDoesNotOverwriteAnotherProduct() {
        Product victim = existingProduct();
        String token = adminToken();

        rest.exchange(url("/api/products"), HttpMethod.POST,
                bearer(token, """
                       {"id":%d,"name":"Hijacked","brand":"Nobody","active":false}
                       """.formatted(victim.getId())),
                String.class);

        // THE FAILING CASE. Before the fix this was an UPDATE: the shop's real
        // product came back renamed and deactivated, and the caller saw 200.
        Product after = products.findById(victim.getId()).orElseThrow();
        assertEquals("Tata Salt 1kg", after.getName(),
                "creating a product overwrote a different product");
        assertEquals("Tata", after.getBrand(), "the victim product's brand was rewritten");
        assertEquals(Boolean.TRUE, after.getActive(),
                "creating a product deactivated a different product");
    }

    @Test
    @DisplayName("creating a product normally still works")
    void createWithoutAnIdStillWorks() {
        String token = adminToken();

        // THE FEATURE MUST SURVIVE THE FIX. Refusing every create would pass
        // the test above and make the admin app useless.
        ResponseEntity<String> response = rest.exchange(url("/api/products"), HttpMethod.POST,
                bearer(token, """
                       {"name":"Aashirvaad Atta 5kg","brand":"Aashirvaad","active":true}
                       """),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "a normal product create was broken: " + response.getBody());
    }
}
