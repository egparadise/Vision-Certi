package com.inviz.visioncerti;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inviz.visioncerti.store.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Every {@link ApiFlowTest} scenario again, with the runtime store on a real PostgreSQL database (V005, non-owner login). */
class PostgresApiFlowTest extends ApiFlowTest {

    @Autowired
    private MockMvc postgresMvc;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.create("apiflow").register(registry);
    }

    /** Same checks as the base test, except that persistence is reported truthfully as POSTGRESQL. */
    @Override
    @Test
    void healthIsPublicAndStatesMockMode() throws Exception {
        postgresMvc.perform(get("/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.signatureMode").value("MOCK_NOT_VALID_FOR_CLINICAL_USE"))
                .andExpect(jsonPath("$.clinicalUseAllowed").value(false))
                .andExpect(jsonPath("$.persistence").value("POSTGRESQL"));
    }
}
