package com.gpstore.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@TestPropertySource(properties = {
        "app.git-commit=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "app.version=test-version",
        "app.production=false"
})
class VersionEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/version is public and returns the baked git SHA")
    void versionIsPublic() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("gp-store-backend"))
                .andExpect(jsonPath("$.version").value("test-version"))
                .andExpect(jsonPath("$.gitCommit").value("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andExpect(jsonPath("$.environment").value("development"))
                .andExpect(jsonPath("$.jwtSecret").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}
