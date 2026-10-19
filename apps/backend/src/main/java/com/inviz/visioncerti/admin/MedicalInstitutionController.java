package com.inviz.visioncerti.admin;

import com.inviz.visioncerti.security.RequestActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/medical-institutions")
public final class MedicalInstitutionController {
    private final MedicalInstitutionService institutions;

    public MedicalInstitutionController(MedicalInstitutionService institutions) {
        this.institutions = institutions;
    }

    @GetMapping
    ResponseEntity<List<MedicalInstitutionService.InstitutionView>> list(RequestActor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(institutions.list(actor));
    }

    @PostMapping
    ResponseEntity<MedicalInstitutionService.InstitutionView> create(RequestActor actor,
            @RequestBody MedicalInstitutionService.CreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(institutions.create(actor, body));
    }

    @PostMapping("/{institutionId}/doctors/{doctorId}")
    ResponseEntity<MedicalInstitutionService.InstitutionView> assign(RequestActor actor, @PathVariable UUID institutionId,
            @PathVariable UUID doctorId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(institutions.assign(actor, institutionId, doctorId));
    }

    @DeleteMapping("/{institutionId}/doctors/{doctorId}")
    ResponseEntity<MedicalInstitutionService.InstitutionView> unassign(RequestActor actor, @PathVariable UUID institutionId,
            @PathVariable UUID doctorId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(institutions.unassign(actor, institutionId, doctorId));
    }
}
