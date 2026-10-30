package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Together these two profiles exercise JDBC discovery across an application restart. */
@QuarkusTest
@TestProfile(PostgresDriverFirstProfileIntegrationTest.FirstProfile.class)
class PostgresDriverFirstProfileIntegrationTest {
    @Test
    void postgresDriverIsAvailableInThisProfile() throws SQLException {
        String url = "jdbc:postgresql://localhost/test";
        assertTrue(DriverManager.getDriver(url).acceptsURL(url));
    }

    public static class FirstProfile implements QuarkusTestProfile {
    }
}
