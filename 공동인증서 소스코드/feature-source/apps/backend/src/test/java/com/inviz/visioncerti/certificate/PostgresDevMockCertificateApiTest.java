package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.store.PostgresTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every {@link DevMockCertificateApiTest} scenario again, with the runtime store on a real PostgreSQL database (V005, non-owner login). */
class PostgresDevMockCertificateApiTest extends DevMockCertificateApiTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.create("devmock").register(registry);
    }
}
