package com.inviz.visioncerti.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit record. {@code hash} chains each event to the previous one of the same tenant
 * (SHA-256 over the previous hash and this event's fields). This in-memory chain is tamper-evident only
 * within the process; it is not the WORM checkpointing required for production.
 */
public record AuditEvent(
        UUID id,
        String tenantId,
        long sequence,
        Instant occurredAt,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String outcome,
        String correlationId,
        Map<String, String> details,
        String previousHash,
        String hash) {
}
