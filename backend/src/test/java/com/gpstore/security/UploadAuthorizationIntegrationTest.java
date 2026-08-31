package com.gpstore.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. A running drain turns committed work into
        // auto-assigned deliveries against whichever rider is available, and
        // Spring caches this context and never closes it - so the worker
        // outlives the class and keeps assigning while later classes are
        // asserting. That is how TerritoryDispatchTest failed with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order and the tie broke the other
        // way.
        //
        // Nothing in this class tests the outbox or waits on an async side
        // effect, so the drain has no purpose here beyond causing that.
        // OutboxDurabilityTest, which does test it, keeps a live worker.
        "outbox.drain-interval-ms=3600000"
})
@AutoConfigureMockMvc
class UploadAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String SIGN_BODY = """
            {"imageType":"PRODUCT","contentType":"image/jpeg","contentLength":1024}
            """;

    @Test
    void unauthenticatedSignIsDenied() throws Exception {
        mockMvc.perform(post("/api/uploads/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIGN_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotSignAdminUploads() throws Exception {
        mockMvc.perform(post("/api/uploads/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIGN_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/uploads/sign-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + SIGN_BODY + "]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotConfirmOrDeleteUploads() throws Exception {
        mockMvc.perform(post("/api/uploads/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"gpstore/products/1/original/a.jpg\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/uploads/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicUrl\":\"https://cdn.example.r2.dev/gpstore/products/1/original/a.jpg\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithStaff
    void adminWrongMimeIsDeniedBeforeStorage() throws Exception {
        mockMvc.perform(post("/api/uploads/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageType":"PRODUCT","contentType":"image/svg+xml","contentLength":1024}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithStaff
    void adminOversizedUploadIsDenied() throws Exception {
        mockMvc.perform(post("/api/uploads/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageType":"PRODUCT","contentType":"image/jpeg","contentLength":99999999}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithStaff
    void adminInvalidObjectPathOnConfirmIsDenied() throws Exception {
        mockMvc.perform(post("/api/uploads/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"../etc/passwd\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithStaff
    void adminBatchLargerThanTwentyIsDenied() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append(SIGN_BODY.trim());
        }
        mockMvc.perform(post("/api/uploads/sign-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedR2ConnectionTestIsDenied() throws Exception {
        mockMvc.perform(post("/api/uploads/r2-connection-test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotRunR2ConnectionTest() throws Exception {
        mockMvc.perform(post("/api/uploads/r2-connection-test"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithStaff
    void adminCloudinarySignatureIsOffByDefault() throws Exception {
        mockMvc.perform(get("/api/uploads/cloudinary-signature"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithStaff
    void adminR2ConnectionTestFailsClosedWhenR2IsUnset() throws Exception {
        mockMvc.perform(post("/api/uploads/r2-connection-test"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotStartCloudinaryToR2Copy() throws Exception {
        mockMvc.perform(post("/api/admin/catalog/images/migrate-to-r2")
                        .param("confirm", "true"))
                .andExpect(status().isForbidden());
    }
}
