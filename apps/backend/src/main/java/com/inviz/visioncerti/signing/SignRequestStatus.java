package com.inviz.visioncerti.signing;

/** States from the architecture note (02_아키텍처_보안경계, 상태와 장애 경계). */
public enum SignRequestStatus {
    PENDING_APPROVAL,
    APPROVED,
    SIGNING,
    EVIDENCE_PENDING,
    COMPLETED,
    EXPIRED,
    REJECTED,
    FAILED,
    RECONCILIATION_REQUIRED
}
