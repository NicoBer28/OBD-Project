package com.obd.api;

import com.obd.api.support.PostgresContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the whole application - every bean, the real security filter chain, and
 * Flyway against a real Postgres. This is the test that catches wiring mistakes
 * the slices cannot see.
 *
 * It used to need a Postgres running on localhost; it now brings its own
 * container, so `./mvnw test` works on a clean checkout with only Docker.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
@TestPropertySource(properties = {
        "DB_USER=unused",
        "DB_PASSWORD=unused",
        "JWT_SECRET=b2JkLXRlc3Qtc2VjcmV0LWtleS0zMi1ieXRlcy1vayE="
})
class ApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
