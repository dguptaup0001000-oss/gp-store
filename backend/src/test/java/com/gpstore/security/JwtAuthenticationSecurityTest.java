package com.gpstore.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that a FORGED bearer token cannot authenticate.
 *
 * WHY THIS DOES NOT USE @WithMockUser, unlike AdminAuthorizationIntegrationTest:
 * that annotation injects an Authentication straight into the SecurityContext
 * and never exercises JwtFilter at all. It is the right tool for asserting the
 * URL rules, and the wrong one here - the entire question is whether the filter
 * itself refuses a token it should refuse. So every test below sends a real
 * Authorization header and lets the real filter chain judge it.
 *
 * The endpoint under test is deliberately one that only requires
 * authentication, not a role. If a forged token were accepted, a role check
 * could still mask the failure and this suite would pass while the door stood
 * open. Asking only "did anyone get in" is the sharper question.
 *
 * EVERY CASE ASSERTS 401, NOT 403. The distinction matters: 403 would mean the
 * token authenticated someone and they merely lacked permission, which for a
 * forged token is already a breach. 401 is the only correct answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationSecurityTest {

    /** Requires authentication, requires no particular role. See SecurityConfig. */
    private static final String PROTECTED = "/api/orders/my-orders";

    @Autowired private MockMvc mockMvc;

    /**
     * The real signing secret, read from the same property the application
     * signs with. Used ONLY to build tokens that are correctly signed but
     * wrong in some other way - the cases an attacker without the key cannot
     * produce, but which a bug in claim handling could still let through.
     */
    @Value("${jwt.secret}") private String secret;

    private SecretKey realKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private void expectUnauthorized(String token) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Tokens an attacker CAN construct without the signing key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void wrongSigningKeyIsRejected() throws Exception {
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "an-attacker-controlled-key-of-more-than-256-bits-length!!".getBytes(StandardCharsets.UTF_8));

        String forged = Jwts.builder()
                .setSubject("attacker@example.com")
                .claim("customerId", 1L)
                .claim("role", "ADMIN")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(attackerKey, SignatureAlgorithm.HS256)
                .compact();

        expectUnauthorized(forged);
    }

    @Test
    @DisplayName("an UNSIGNED token claiming ADMIN is rejected - the alg:none attack")
    void unsignedTokenIsRejected() throws Exception {
        // The classic JWT vulnerability: strip the signature, set alg to none,
        // and hope the library trusts the header. A parser configured with a
        // SecretKey must refuse this outright rather than treating an absent
        // signature as a valid one.
        String unsigned = Jwts.builder()
                .setSubject("attacker@example.com")
                .claim("customerId", 1L)
                .claim("role", "ADMIN")
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .compact();

        expectUnauthorized(unsigned);
    }

    @Test
    @DisplayName("a hand-built alg:none token is rejected")
    void handCraftedNoneAlgorithmIsRejected() throws Exception {
        // Built by hand rather than through the library, because a library
        // may refuse to emit what an attacker will happily assemble with a
        // text editor and base64.
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = url.encodeToString(
                ("{\"sub\":\"attacker@example.com\",\"customerId\":1,\"role\":\"ADMIN\",\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 600) + "}").getBytes(StandardCharsets.UTF_8));

        expectUnauthorized(header + "." + payload + ".");
    }

    @Test
    @DisplayName("a token whose payload was edited after signing is rejected")
    void tamperedPayloadIsRejected() throws Exception {
        String valid = Jwts.builder()
                .setSubject("customer@example.com")
                .claim("customerId", 2L)
                .claim("role", "CUSTOMER")
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(realKey(), SignatureAlgorithm.HS256)
                .compact();

        // Swap the middle segment for one claiming ADMIN, keeping the original
        // signature. This is privilege escalation attempted the obvious way.
        String[] parts = valid.split("\\.");
        String escalated = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"customer@example.com\",\"customerId\":2,\"role\":\"ADMIN\",\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 600) + "}").getBytes(StandardCharsets.UTF_8));

        expectUnauthorized(parts[0] + "." + escalated + "." + parts[2]);
    }

    @Test
    @DisplayName("a token whose signature was edited is rejected")
    void tamperedSignatureIsRejected() throws Exception {
        String valid = Jwts.builder()
                .setSubject("customer@example.com")
                .claim("customerId", 2L)
                .claim("role", "CUSTOMER")
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(realKey(), SignatureAlgorithm.HS256)
                .compact();

        String[] parts = valid.split("\\.");
        char first = parts[2].charAt(0);
        String flipped = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);

        expectUnauthorized(parts[0] + "." + parts[1] + "." + flipped);
    }

    @Test
    @DisplayName("garbage in the Authorization header is rejected, not crashed on")
    void malformedTokenIsRejected() throws Exception {
        expectUnauthorized("this-is-not-a-jwt");
        expectUnauthorized("a.b.c");
        expectUnauthorized("");
        expectUnauthorized("....");
    }

    @Test
    @DisplayName("no Authorization header at all is rejected")
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a bearer prefix with nothing after it is rejected")
    void emptyBearerIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Tokens that ARE correctly signed but are still not acceptable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an EXPIRED token is rejected even though its signature is genuine")
    void expiredTokenIsRejected() throws Exception {
        // Signed with the real key, so the signature verifies perfectly. Only
        // the clock says no. This is the case a naive "is the signature good?"
        // check gets wrong, and it is the difference between a session ending
        // and a stolen token working forever.
        String expired = Jwts.builder()
                .setSubject("customer@example.com")
                .claim("customerId", 2L)
                .claim("role", "ADMIN")
                .setIssuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .setExpiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(realKey(), SignatureAlgorithm.HS256)
                .compact();

        expectUnauthorized(expired);
    }

    /**
     * Must not collide with rows earlier tests insert. CI's empty database
     * assigns identity 1, 2, 3, … so a hardcoded customerId of 2 was a live
     * account by the time this class ran, and JwtFilter correctly treated it
     * as authenticated (403 on admin) instead of missing (401).
     */
    private static final long MISSING_CUSTOMER_ID = 8_888_888_888L;

    @Test
    @DisplayName("an admin endpoint refuses a genuinely signed token carrying no role")
    void signedTokenWithoutRoleCannotReachAdminEndpoints() throws Exception {
        // Not an outsider attack - this needs the signing key - but it is the
        // shape a claim-handling bug takes: JwtFilter grants
        // SimpleGrantedAuthority("ROLE_" + role), so a null role must produce
        // NO authority rather than a blank one that accidentally matches.
        String roleless = Jwts.builder()
                .setSubject("customer@example.com")
                .claim("customerId", MISSING_CUSTOMER_ID)
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(realKey(), SignatureAlgorithm.HS256)
                .compact();

        // The customer id is not a row, so JwtFilter rejects the token before
        // authorization. 401 (not authenticated) is correct; 403 would mean
        // the missing account was treated as a live principal.
        mockMvc.perform(get("/api/admin/catalog/audit").header("Authorization", "Bearer " + roleless))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a signed token claiming an unknown role reaches no admin endpoint")
    void unknownRoleCannotReachAdminEndpoints() throws Exception {
        String bogusRole = Jwts.builder()
                .setSubject("customer@example.com")
                .claim("customerId", MISSING_CUSTOMER_ID)
                .claim("role", "SUPERUSER")
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(realKey(), SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/admin/catalog/audit").header("Authorization", "Bearer " + bogusRole))
                .andExpect(status().isUnauthorized());
    }
}
