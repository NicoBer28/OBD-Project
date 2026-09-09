package com.obd.api.support;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.*;

/**
 * Repository-layer test slice, backed by a real Postgres (see
 * {@link PostgresContainerConfig}).
 *
 * Two deliberate choices:
 *  - replace = NONE keeps Boot from swapping in an embedded database, which is
 *    the whole point: Flyway builds the schema and Hibernate validates the
 *    entities against it, so entity/migration drift fails here instead of at
 *    startup in production.
 *  - DB_USER/DB_PASSWORD are supplied because application.properties reads them
 *    as required placeholders. @ServiceConnection overrides the actual
 *    connection, so these values are never used to connect - they only keep
 *    placeholder resolution happy.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresContainerConfig.class)
@TestPropertySource(properties = {
        "DB_USER=unused",
        "DB_PASSWORD=unused",
        "JWT_SECRET=b2JkLXRlc3Qtc2VjcmV0LWtleS0zMi1ieXRlcy1vayE="
})
public @interface RepositoryTest {
}
