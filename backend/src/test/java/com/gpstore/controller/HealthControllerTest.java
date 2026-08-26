package com.gpstore.controller;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class HealthControllerTest {

    @Test
    void busyPoolWithoutAPriorProbeStillRunsSelectOne() throws SQLException {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getIdleConnections()).thenReturn(0);

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
    void busyPoolAfterASuccessfulProbeSkipsBorrowing() throws SQLException {
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(mx.getIdleConnections()).thenReturn(2).thenReturn(0);

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

        // Force the 2s ready cache to expire so the busy-skip path is taken.
        setLastReadyOkFarInThePast(controller);

        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());
        verify(hikari, times(1)).getConnection();
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

    @Test
    void redisDownIsNotReadyEvenWhenPostgresIsUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenThrow(new RuntimeException("Redis unavailable"));

        HealthController controller = new HealthController(dataSource, redis);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.ready().getStatusCode());
        verify(statement).execute("SELECT 1");
    }

    @Test
    void redisPingSuccessIsReady() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection redisConnection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        HealthController controller = new HealthController(dataSource, redis);
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());
        verify(redisConnection).close();
    }

    private static void setLastReadyOkFarInThePast(HealthController controller) {
        try {
            var field = HealthController.class.getDeclaredField("lastReadyOkAt");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) field.get(controller)).set(1L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
