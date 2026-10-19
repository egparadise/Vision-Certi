package com.inviz.visioncerti.admin;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Tenant-scoped institution directory. A name is an administrator entry, not external licence verification. */
@Service
public final class MedicalInstitutionService {
    public record Institution(UUID id, String tenantId, String code, String name, Instant createdAt, String createdBy) { }
    public record Assignment(UUID id, String tenantId, UUID institutionId, UUID doctorId,
            Instant assignedAt, String assignedBy) { }
    public record InstitutionView(UUID id, String code, String name, List<UUID> doctorIds, Instant createdAt) { }
    public record CreateRequest(String code, String name) { }

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{2,40}");
    private static final Kind<Institution> INSTITUTIONS = Kind.of("medical_institutions", Institution.class)
            .id(Institution::id).tenant(Institution::tenantId)
            .unique("code_key", i -> key(i.tenantId(), i.code()),
                    () -> ApiException.conflict("INSTITUTION_CODE_EXISTS", "기관 코드가 이미 등록되어 있습니다.")).build();
    private static final Kind<Assignment> ASSIGNMENTS = Kind.of("medical_institution_doctors", Assignment.class)
            .id(Assignment::id).tenant(Assignment::tenantId)
            .unique("assignment_key", a -> key(a.tenantId(), a.institutionId() + ":" + a.doctorId()),
                    () -> ApiException.conflict("INSTITUTION_ASSIGNMENT_EXISTS", "이미 배정된 의사입니다."))
            .column("doctor_id", a -> a.doctorId().toString()).build();

    private final Store store;
    private final AuditService audit;
    private final DoctorService doctors;
    private final Clock clock;

    public MedicalInstitutionService(AuditService audit, DoctorService doctors, Clock clock) {
        this.store = audit.store();
        this.audit = audit;
        this.doctors = doctors;
        this.clock = clock;
    }

    public List<InstitutionView> list(RequestActor actor) {
        requireAdmin(actor);
        return store.tx(tx -> {
            List<Assignment> assignments = tx.find(ASSIGNMENTS, Kind.TENANT, actor.tenantId());
            return tx.find(INSTITUTIONS, Kind.TENANT, actor.tenantId()).stream()
                    .map(i -> new InstitutionView(i.id(), i.code(), i.name(), assignments.stream()
                            .filter(a -> a.institutionId().equals(i.id())).map(Assignment::doctorId).toList(),
                            i.createdAt())).toList();
        });
    }

    public InstitutionView create(RequestActor actor, CreateRequest body) {
        requireAdmin(actor);
        if (body == null || body.code() == null || !CODE.matcher(body.code().strip()).matches()
                || body.name() == null || body.name().strip().length() < 2 || body.name().strip().length() > 100) {
            throw ApiException.unprocessable("INSTITUTION_INVALID", "기관 코드(2~40자)와 기관명(2~100자)이 필요합니다.");
        }
        String code = body.code().strip();
        String name = body.name().strip();
        Institution institution = new Institution(UUID.randomUUID(), actor.tenantId(), code, name,
                clock.instant(), actor.actorId());
        store.run(tx -> {
            tx.lock("institution-code|" + key(actor.tenantId(), code));
            if (tx.findOne(INSTITUTIONS, "code_key", key(actor.tenantId(), code)).isPresent()) {
                throw ApiException.conflict("INSTITUTION_CODE_EXISTS", "기관 코드가 이미 등록되어 있습니다.");
            }
            tx.insert(INSTITUTIONS, institution);
            audit.record(actor.tenantId(), actor.actorId(), "MEDICAL_INSTITUTION_REGISTERED", "MEDICAL_INSTITUTION",
                    institution.id().toString(), AuditService.SUCCESS, Map.of("code", code, "name", name));
        });
        return new InstitutionView(institution.id(), code, name, List.of(), institution.createdAt());
    }

    public InstitutionView assign(RequestActor actor, UUID institutionId, UUID doctorId) {
        requireAdmin(actor);
        doctors.get(actor, doctorId); // Refuses a foreign or nonexistent doctor.
        store.run(tx -> {
            Institution institution = tx.get(INSTITUTIONS, institutionId)
                    .filter(i -> i.tenantId().equals(actor.tenantId()))
                    .orElseThrow(() -> ApiException.notFound("INSTITUTION_NOT_FOUND", "등록된 의료기관이 없습니다."));
            String assignmentKey = key(actor.tenantId(), institutionId + ":" + doctorId);
            tx.lock("institution-assignment|" + assignmentKey);
            if (tx.findOne(ASSIGNMENTS, "assignment_key", assignmentKey).isEmpty()) {
                tx.insert(ASSIGNMENTS, new Assignment(UUID.randomUUID(), actor.tenantId(), institutionId,
                        doctorId, clock.instant(), actor.actorId()));
                audit.record(actor.tenantId(), actor.actorId(), "MEDICAL_INSTITUTION_DOCTOR_ASSIGNED",
                        "DOCTOR", doctorId.toString(), AuditService.SUCCESS,
                        Map.of("institutionId", institution.id().toString(), "institutionCode", institution.code()));
            }
        });
        return list(actor).stream().filter(i -> i.id().equals(institutionId)).findFirst().orElseThrow();
    }

    public InstitutionView unassign(RequestActor actor, UUID institutionId, UUID doctorId) {
        requireAdmin(actor);
        store.run(tx -> {
            Institution institution = tx.get(INSTITUTIONS, institutionId)
                    .filter(i -> i.tenantId().equals(actor.tenantId()))
                    .orElseThrow(() -> ApiException.notFound("INSTITUTION_NOT_FOUND", "등록된 의료기관이 없습니다."));
            String assignmentKey = key(actor.tenantId(), institutionId + ":" + doctorId);
            tx.lock("institution-assignment|" + assignmentKey);
            tx.findOne(ASSIGNMENTS, "assignment_key", assignmentKey).ifPresent(a -> {
                tx.delete(ASSIGNMENTS, a.id());
                audit.record(actor.tenantId(), actor.actorId(), "MEDICAL_INSTITUTION_DOCTOR_UNASSIGNED",
                        "DOCTOR", doctorId.toString(), AuditService.SUCCESS,
                        Map.of("institutionId", institution.id().toString(), "institutionCode", institution.code()));
            });
        });
        return list(actor).stream().filter(i -> i.id().equals(institutionId)).findFirst().orElseThrow();
    }

    public List<String> institutionsFor(RequestActor actor, UUID doctorId) {
        requireAdmin(actor);
        return store.tx(tx -> {
            List<Institution> institutions = tx.find(INSTITUTIONS, Kind.TENANT, actor.tenantId());
            return tx.find(ASSIGNMENTS, "doctor_id", doctorId.toString()).stream()
                    .filter(a -> a.tenantId().equals(actor.tenantId()))
                    .flatMap(a -> institutions.stream().filter(i -> i.id().equals(a.institutionId())))
                    .map(Institution::name).toList();
        });
    }

    private static String key(String tenantId, String value) {
        return Hashing.sha256Hex(tenantId + "\u0000" + value);
    }

    private static void requireAdmin(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_REQUIRED", "관리자 권한이 필요합니다.");
        }
    }
}
