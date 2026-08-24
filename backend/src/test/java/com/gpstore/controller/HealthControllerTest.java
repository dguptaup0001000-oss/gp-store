package com.gpstore.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class HealthControllerTest {

    @Test
    void busyPoolIsReadyWithoutBorrowingAConnection() throws SQLException {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getIdleConnections()).thenReturn(0);

        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.isRunning()).thenReturn(true);
        when(hikari.getHikariPoolMXBean()).thenReturn(mx);

        HealthController controller = new HealthController(hikari);
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());
        verify(hikari, never()).getConnection();
    }

    @Test
    void idlePoolStillRunsSelectOne() throws SQLException {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getIdleConnections()).thenReturn(2);

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.isRunning()).thenReturn(true);
        when(hikari.getHikariPoolMXBean()).thenReturn(mx);
        when(hikari.getConnection()).thenReturn(connection);

        HealthController controller = new HealthController(hikari);
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());
        verify(statement).execute("SELECT 1");
    }

    @Test
    void stoppedPoolIsNotReady() {
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.isRunning()).thenReturn(false);

        HealthController controller = new HealthController(hikari);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.ready().getStatusCode());
    }

    @Test
    void recentSuccessIsCached() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);

        HealthController controller = new HealthController(dataSource);
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());
        verify(dataSource, times(1)).getConnection();
    }
}
