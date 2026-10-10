package com.landrop.database;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionConfigTest {
    @Test void encodedCredentialsAndQueryValuesRemainIntact() {
        var config = new HikariConfig();
        DatabaseManager.configureConnection(config,
            "postgresql://user%3Aname:p%40ss%3Aword+%26@localhost/test?sslmode=require&options=a%26b&channel_binding=require");
        assertEquals("user:name", config.getUsername());
        assertEquals("p@ss:word+&", config.getPassword());
        assertEquals("jdbc:postgresql://localhost:5432/test?sslmode=require&options=a%26b", config.getJdbcUrl());
    }

    @Test void queryCredentialsAreRemovedFromJdbcUrl() {
        var config = new HikariConfig();
        DatabaseManager.configureConnection(config,
            "jdbc:postgresql://localhost:5433/test?user=tester&password=secret%26%2B&sslmode=disable");
        assertEquals("tester", config.getUsername());
        assertEquals("secret&+", config.getPassword());
        assertEquals("jdbc:postgresql://localhost:5433/test?sslmode=disable", config.getJdbcUrl());
    }

    @Test void malformedConfigurationFailsWithoutDisclosingCredentials() {
        for (String url : new String[]{"https://user:secret@localhost/test", "postgresql:///test", "postgresql://user:secret password@localhost/test"}) {
            var error = assertThrows(IllegalArgumentException.class,
                () -> DatabaseManager.configureConnection(new HikariConfig(), url));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
        }
    }
}
