package com.inviz.visioncerti.certificate;
import com.inviz.visioncerti.store.PostgresTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
class PostgresPhysicianAccountApiTest extends PhysicianAccountApiTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) {
        PostgresTestDatabase.create("physicianaccounts").register(r);
    }
}
