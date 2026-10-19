package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.store.PostgresTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every {@link PacsIntegrationApiTest} scenario again, with the runtime store on a real PostgreSQL database (V005, non-owner login). */
class PostgresPacsIntegrationApiTest extends PacsIntegrationApiTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.create("pacsapi").register(registry);
    }
}
