package com.inviz.visioncerti.delivery;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.delivery.Delivery.DeliveryStatus;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Durable delivery outbox (queue only; no sender exists, entries stay QUEUED). */
@Service
public class DeliveryService {

    static final Kind<Delivery> DELIVERIES = Kind.of("deliveries", Delivery.class)
            .id(Delivery::id).tenant(Delivery::tenantId)
            .column("signature_id", d -> d.signatureId().toString())
            .build();

    private final Store store;
    private final AuditService audit;
    private final Clock clock;

    public DeliveryService(AuditService audit, Clock clock) {
        this.store = audit.store();
        this.audit = audit;
        this.clock = clock;
    }

    public Delivery enqueue(String tenantId, String actorId, UUID signatureId, UUID signRequestId,
            UUID documentId) {
        Instant now = clock.instant();
        Delivery delivery = new Delivery(UUID.randomUUID(), tenantId, signatureId, signRequestId, documentId,
                "mock-connector", "SIGNATURE_COMPLETED", "mock://" + tenantId + "/report-receiver",
                DeliveryStatus.QUEUED, 0, now, now);
        store.run(tx -> {
            tx.insert(DELIVERIES, delivery);
            audit.record(tenantId, actorId, "DELIVERY_QUEUED", "DELIVERY", delivery.id().toString(),
                    AuditService.SUCCESS, Map.of("signatureId", signatureId.toString(),
                            "destination", delivery.destination()));
        });
        return delivery;
    }

    public Delivery get(String tenantId, UUID id) {
        return store.tx(tx -> tx.get(DELIVERIES, id)).filter(d -> d.tenantId().equals(tenantId))
                .orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", "Delivery not found"));
    }

    /** Newest first. */
    public List<Delivery> list(String tenantId) {
        List<Delivery> result = new ArrayList<>(store.tx(tx -> tx.find(DELIVERIES, Kind.TENANT, tenantId)));
        result.sort(Comparator.comparing(Delivery::createdAt).reversed().thenComparing(Delivery::id));
        return result;
    }
}
