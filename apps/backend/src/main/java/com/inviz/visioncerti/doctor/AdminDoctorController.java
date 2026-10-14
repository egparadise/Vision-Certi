package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ChangeDoctorStatus;
import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregisterDoctor;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregistrationResponse;
import com.inviz.visioncerti.security.RequestActor;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin preregistration and status control (dev header identity, role ADMIN). Cannot consent or activate. */
@RestController
@RequestMapping("/v1/admin/doctors")
public class AdminDoctorController {

    private final DoctorService doctors;

    public AdminDoctorController(DoctorService doctors) {
        this.doctors = doctors;
    }

    @PostMapping
    ResponseEntity<PreregistrationResponse> preregister(RequestActor actor, @RequestBody PreregisterDoctor body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(doctors.preregister(actor, body));
    }

    @GetMapping
    ListResponse<DoctorResponse> list(RequestActor actor) {
        return ListResponse.of(doctors.list(actor));
    }

    @GetMapping("/{id}")
    DoctorResponse get(RequestActor actor, @PathVariable UUID id) {
        return doctors.get(actor, id);
    }

    @GetMapping("/{id}/evidence")
    ListResponse<DoctorEvidence> evidence(RequestActor actor, @PathVariable UUID id) {
        return ListResponse.of(doctors.evidence(actor, id));
    }

    @PostMapping("/{id}/invitation")
    ResponseEntity<PreregistrationResponse> reissue(RequestActor actor, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(doctors.reissueInvitation(actor, id));
    }

    @PatchMapping("/{id}/status")
    DoctorResponse changeStatus(RequestActor actor, @PathVariable UUID id, @RequestBody ChangeDoctorStatus body) {
        return doctors.changeStatus(actor, id, body);
    }
}
