package com.inviz.visioncerti.audit;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/audit-events")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ListResponse<AuditEvent> list(RequestActor actor) {
        var events = auditService.list(actor.tenantId());
        if (actor.role() != ActorRole.ADMIN) {
            events = events.stream()
                    .filter(event -> !"PACS_CERTIFICATE_LOGIN_COMPLETED".equals(event.action()))
                    .toList();
        }
        return ListResponse.of(events);
    }

    /** The PACS login ID and certificate fingerprint are visible only to a tenant administrator. */
    @GetMapping("/certificate-logins")
    public ListResponse<AuditEvent> certificateLogins(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_REQUIRED", "Administrator access is required");
        }
        return ListResponse.of(auditService.list(actor.tenantId()).stream()
                .filter(event -> "PACS_CERTIFICATE_LOGIN_COMPLETED".equals(event.action()))
                .limit(200).toList());
    }
}
