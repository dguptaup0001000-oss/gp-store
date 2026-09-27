package com.gpstore.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class IntelligenceRetentionJobTest {
    @Mock private JdbcTemplate jdbc;

    @Test
    void expirationIsAuditedBeforeRawSignalsAreDeleted() {
        IntelligenceRetentionJob job = new IntelligenceRetentionJob(jdbc, 90);

        job.clean();

        InOrder ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(contains("REQUEST_EXPIRED"));
        ordered.verify(jdbc).update(contains("SET status='EXPIRED'"));
        ordered.verify(jdbc).update(
                contains("DELETE FROM marketplace_search_events"), org.mockito.ArgumentMatchers.eq(90));
    }
}
