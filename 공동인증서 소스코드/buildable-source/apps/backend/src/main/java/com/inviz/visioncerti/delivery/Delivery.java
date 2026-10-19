package com.inviz.visioncerti.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox entry for sending a completed signature back to the hospital. No delivery worker exists in this
 * slice, so entries remain {@code QUEUED}; {@code destination} is a synthetic {@code mock://} address.
 */
public record Delivery(
        UUID id,
        String tenantId,
        UUID signatureId,
        UUID signRequestId,
        UUID documentId,
        String connectorId,
        String eventType,
        String destination,
        DeliveryStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt) {

    /** Contract values (contracts/openapi.dev.json). Only QUEUED occurs until a sender exists. */
    public enum DeliveryStatus {
        QUEUED,
        SENDING,
        ACKED,
        RETRY_WAIT,
        DEAD_LETTER
    }
}
