package com.smartparking.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExistingEndpointsTest {
    @Test
    void databaseConnectionEndpointRetainsItsResponseContract() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 'Connected' FROM dual", String.class)).thenReturn("Connected");

        assertEquals(Map.of("status", "Connected", "database", "Oracle XE"),
                new DatabaseController(jdbc).testConnection());
    }

    @Test
    void healthEndpointRemainsAvailable() {
        assertEquals("Smart Parking Backend is running!", new HealthController().healthCheck());
    }
}
