package com.gpstore.intelligence;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MarketplaceSignalsAsyncTest {
    @Test
    void searchQueuesSignalWithoutTouchingDatabaseUntilBackgroundFlush() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        MarketplaceSignals signals = new MarketplaceSignals(jdbc, currentUser);

        signals.search("rice", 26.75, 83.37, Set.of(CommerceMode.ONLINE_PURCHASE), 4);

        assertEquals(1, signals.queuedForTests());
        verifyNoInteractions(jdbc);

        signals.flushPending();

        verify(jdbc).batchUpdate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.<ParameterizedPreparedStatementSetter<MarketplaceSignals.SearchSignal>>any());
        assertEquals(0, signals.queuedForTests());
    }
}
