package com.inviz.visioncerti.dashboard;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.delivery.Delivery;
import com.inviz.visioncerti.delivery.DeliveryService;
import com.inviz.visioncerti.document.DocumentRepository;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.SignRequestService;
import com.inviz.visioncerti.signing.SignRequestStatus;
import com.inviz.visioncerti.signing.SignatureService;
import com.inviz.visioncerti.signing.SigningDtos.SignRequestResponse;
import com.inviz.visioncerti.signing.SigningProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/dashboard")
public class DashboardController {

    /** Tenant-scoped counters. Every status key is always present (0 when empty). */
    public record DashboardResponse(
            String tenantId,
            Instant generatedAt,
            String signatureMode,
            String persistence,
            long documents,
            long documentVersions,
            Map<String, Long> signRequestsByStatus,
            long signatures,
            Map<String, Long> deliveriesByStatus,
            long auditEvents) {
    }

    private final DocumentRepository documents;
    private final SignRequestService signRequests;
    private final SignatureService signatures;
    private final DeliveryService deliveries;
    private final AuditService audit;
    private final SigningProvider signingProvider;
    private final Clock clock;

    public DashboardController(DocumentRepository documents, SignRequestService signRequests,
            SignatureService signatures, DeliveryService deliveries, AuditService audit,
            SigningProvider signingProvider, Clock clock) {
        this.documents = documents;
        this.signRequests = signRequests;
        this.signatures = signatures;
        this.deliveries = deliveries;
        this.audit = audit;
        this.signingProvider = signingProvider;
        this.clock = clock;
    }

    @GetMapping
    public DashboardResponse get(RequestActor actor) {
        Map<String, Long> requestsByStatus = new LinkedHashMap<>();
        for (SignRequestStatus status : SignRequestStatus.values()) {
            requestsByStatus.put(status.name(), 0L);
        }
        for (SignRequestResponse r : signRequests.list(actor)) {
            requestsByStatus.merge(r.status().name(), 1L, Long::sum);
        }
        Map<String, Long> deliveriesByStatus = new LinkedHashMap<>();
        for (Delivery.DeliveryStatus status : Delivery.DeliveryStatus.values()) {
            deliveriesByStatus.put(status.name(), 0L);
        }
        for (Delivery d : deliveries.list(actor.tenantId())) {
            deliveriesByStatus.merge(d.status().name(), 1L, Long::sum);
        }
        return new DashboardResponse(actor.tenantId(), clock.instant(), signingProvider.signatureMode(), audit.store().mode(),
                documents.list(actor.tenantId()).size(), documents.versionCount(actor.tenantId()), requestsByStatus,
                signatures.list(actor).size(), deliveriesByStatus, audit.count(actor.tenantId()));
    }
}
