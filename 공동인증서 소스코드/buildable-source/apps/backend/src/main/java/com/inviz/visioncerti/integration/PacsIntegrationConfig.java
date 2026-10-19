package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.IdempotencyService;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.document.DocumentService;
import com.inviz.visioncerti.signing.SignRequestService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the SV70 PACS integration (docs/SV70_PACS_INTEGRATION.md). Unconfigured means 503 on every route. */
@Configuration
public class PacsIntegrationConfig {

    @Bean
    IntegrationClients integrationClients(
            @Value("${visioncerti.pacs.client-id:}") String clientId,
            @Value("${visioncerti.pacs.client-secret-file:}") String secretFile,
            @Value("${visioncerti.pacs.client-secret:}") String secret,
            @Value("${visioncerti.pacs.audience:}") String audience,
            @Value("${visioncerti.pacs.tenant-ids:}") String tenantIds) {
        return IntegrationClients.fromConfig(clientId, secretFile, secret, audience, tenantIds);
    }

    @Bean
    PacsHandoffService pacsHandoffService(IntegrationClients clients, DoctorService doctors, AuditService audit,
            Clock clock, @Value("${visioncerti.pacs.handoff-ttl:PT60S}") Duration ttl,
            @Value("${visioncerti.pacs.max-certificate-login-age:PT10M}") Duration maxLoginAge) {
        return new PacsHandoffService(clients, doctors, audit, clock, ttl, maxLoginAge);
    }

    @Bean
    PacsReportSignService pacsReportSignService(DocumentService documents, SignRequestService signRequests,
            IdempotencyService idempotency, PacsHandoffService handoffs) {
        return new PacsReportSignService(documents, signRequests, idempotency, handoffs);
    }
}
