package com.inviz.visioncerti.audit;

import com.inviz.visioncerti.common.ListResponse;
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
        return ListResponse.of(auditService.list(actor.tenantId()));
    }
}
