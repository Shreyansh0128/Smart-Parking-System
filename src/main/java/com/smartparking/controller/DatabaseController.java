
package com.smartparking.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DatabaseController {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/database/test")
    public Map<String, String> testConnection() {
        String result = jdbcTemplate.queryForObject(
                "SELECT 'Connected' FROM dual",
                String.class
        );

        return Map.of(
                "status", result,
                "database", "Oracle XE"
        );
    }
}
