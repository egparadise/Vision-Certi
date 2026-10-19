package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.store.PostgresTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every {@link DoctorActivationApiTest} scenario again, with the runtime store on a real PostgreSQL database (V005, non-owner login). */
@Import(DoctorActivationApiTest.GoogleTestConfig.class)
class PostgresDoctorActivationApiTest extends DoctorActivationApiTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.create("doctorapi").register(registry);
    }
}
