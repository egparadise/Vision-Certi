package com.inviz.visioncerti.security;

import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.security.StaffDirectory.CreateStaffRequest;
import com.inviz.visioncerti.security.StaffDirectory.DisableStaffRequest;
import com.inviz.visioncerti.security.StaffDirectory.StaffPrincipal;
import com.inviz.visioncerti.security.StaffDirectory.StaffPrincipalResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated staff member's own view, and tenant administration of staff mappings (ADMIN only). */
@RestController
public class StaffController {

    /** {@code principalId}, {@code issuer} and {@code displayName} are null for dev header actors. */
    public record StaffMeResponse(String tenantId, String actorId, String role, String authMode, UUID principalId,
            String issuer, String displayName) {
    }

    private final StaffDirectory directory;

    public StaffController(StaffDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/v1/staff/me")
    public ResponseEntity<StaffMeResponse> me(RequestActor actor, HttpServletRequest request) {
        Object attribute = request.getAttribute(StaffAuthFilter.PRINCIPAL_ATTRIBUTE);
        StaffMeResponse body = attribute instanceof StaffPrincipal p
                ? new StaffMeResponse(actor.tenantId(), actor.actorId(), actor.role().name(),
                        SyntheticActorsCondition.OIDC, p.id(), p.issuer(), p.displayName())
                : new StaffMeResponse(actor.tenantId(), actor.actorId(), actor.role().name(),
                        SyntheticActorsCondition.DEV_HEADERS, null, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/v1/admin/staff")
    public ListResponse<StaffPrincipalResponse> list(RequestActor actor) {
        return ListResponse.of(directory.list(actor));
    }

    @PostMapping("/v1/admin/staff")
    public ResponseEntity<StaffPrincipalResponse> create(RequestActor actor, @RequestBody CreateStaffRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(directory.create(actor, body));
    }

    @PostMapping("/v1/admin/staff/{principalId}/disable")
    public StaffPrincipalResponse disable(RequestActor actor, @PathVariable UUID principalId,
            @RequestBody DisableStaffRequest body) {
        return directory.disable(actor, principalId, body);
    }
}
