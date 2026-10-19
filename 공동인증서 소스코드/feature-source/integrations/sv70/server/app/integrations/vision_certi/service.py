"""Integration logic: certificate-login sessions, explicit doctor links, report prepare/finalize/reconcile, and the
legacy A/RA guard. Framework-free; the HTTP layer is api.py.

Invariants:
- Identity comes only from Vision-Certi's server-side handoff redeem plus a verified account link (never e-mail, never
  a browser-supplied doctorId or "authenticated" flag).
- PACS bearer tokens are random (``vc1.`` + 43 chars), stored as SHA-256 only, expire at
  min(now + session_max_minutes, Vision-Certi session expiry), and are re-validated on every request.
- A/RA is written only after Vision-Certi returns signature evidence that verifies over the stored snapshot bytes and
  fresh local checks pass, in the same transaction as the approval row. Network failure never yields APPROVED.
- Approval rows are never deleted; evidence columns are write-once; a new revision is a new row.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import json
import re
import secrets
import uuid
from dataclasses import dataclass
from typing import Callable, Optional

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import ecdsa_p256, snapshot as snap
from .client import VcRejected, VcUnavailable, VisionCertiClient
from .config import VcConfig
from .legacy_port import LocalUser, ReportInfo, SqlLegacyPort
from .orm import VcAudit, VcDoctorLink, VcReportApproval, VcSession

TOKEN_PREFIX = "vc1."
_TOKEN = re.compile(r"^vc1\.[A-Za-z0-9_-]{43}$")
_HANDOFF = re.compile(r"^[A-Za-z0-9_-]{43}$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_LICENSE = re.compile(r"^[0-9]{1,20}$")  # same rule as Vision-Certi doctor pre-registration
_CERT_MODES = {"PC_CERTIFICATE", "MOBILE_CERTIFICATE"}
_ADMIN_GROUP_LEVEL = 98  # same threshold as router/StudyReport._can_read_others_studies

# Approval states
PREPARING = "PREPARING"
PREPARE_RETRY = "PREPARE_RETRY"
PENDING_SIGNATURE = "PENDING_SIGNATURE"
SIGNED_REMOTE = "SIGNED_REMOTE"
APPROVED = "APPROVED"
EXPIRED = "EXPIRED"
REMOTE_FAILED = "REMOTE_FAILED"
SUPERSEDED = "SUPERSEDED"
SUPERSEDED_AFTER_SIGNATURE = "SUPERSEDED_AFTER_SIGNATURE"
COMMIT_REFUSED = "COMMIT_REFUSED"
OPEN_STATES = (PREPARING, PREPARE_RETRY, PENDING_SIGNATURE)
RECONCILE_STATES = (PREPARING, PREPARE_RETRY, PENDING_SIGNATURE, SIGNED_REMOTE)


class VcError(Exception):
    def __init__(self, status: int, code: str, message: str, *, retryable: bool = False, data: dict | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.data = data or {}


@dataclass(frozen=True)
class VcPrincipal:
    session_idx: int
    user_idx: int
    link_idx: int
    tenant_id: str
    doctor_id: str
    enrollment_id: str
    certificate_fingerprint: str
    identity_mode: str
    expires_at: datetime.datetime
    user: LocalUser


@dataclass(frozen=True)
class VerifiedIdentity:
    """Identity returned by Vision-Certi's server-side handoff redeem, after local validation."""
    tenant_id: str
    doctor_id: str
    enrollment_id: str
    certificate_fingerprint: str
    identity_mode: str
    authenticated_at: datetime.datetime
    session_expires_at: datetime.datetime
    license_number: object  # as sent by Vision-Certi (admin-verified there); validated by _require_license_match


def _license_digits(value) -> str:
    return re.sub(r"[^0-9]", "", value) if isinstance(value, str) else ""


def utcnow() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


def _parse_instant(value) -> datetime.datetime:
    if not isinstance(value, str):
        raise ValueError("not an instant")
    parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(datetime.timezone.utc).replace(tzinfo=None)
    return parsed


def _uuid(value) -> Optional[str]:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError):
        return None


def token_sha256(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class VcIntegrationService:
    def __init__(self, config: VcConfig, client: VisionCertiClient, port: SqlLegacyPort,
                 clock: Callable[[], datetime.datetime] = utcnow, super_admins=()):
        self.config = config
        self.client = client
        self.port = port
        self.clock = clock
        self.super_admins = frozenset(int(x) for x in super_admins)

    # ------------------------------------------------------------------ helpers

    def require_operational(self) -> None:
        if not self.config.operational:
            if self.config.guard_active:
                raise VcError(503, "VC_INTEGRATION_MISCONFIGURED",
                              "Vision-Certi 연동 설정이 올바르지 않아 인증서 기능을 사용할 수 없습니다.")
            raise VcError(404, "VC_INTEGRATION_DISABLED", "Vision-Certi 연동이 꺼져 있습니다.")

    def _audit(self, session: Session, actor: Optional[int], action: str, target_type: str, target_id,
               outcome: str, **detail) -> None:
        session.add(VcAudit(at=self.clock(), actor_user_idx=actor, action=action, target_type=target_type,
                            target_id=None if target_id is None else str(target_id)[:64], outcome=outcome,
                            detail_json=json.dumps(detail, ensure_ascii=False, sort_keys=True) if detail else None))

    def is_link_admin(self, user_idx: Optional[int]) -> bool:
        return user_idx is not None and int(user_idx) in self.config.link_admin_user_idx

    def _can_act_on_others(self, user: LocalUser) -> bool:
        return user.group_level >= _ADMIN_GROUP_LEVEL or user.user_idx in self.super_admins

    def _may_write_report(self, user: LocalUser, study, report: Optional[ReportInfo]) -> bool:
        """Same rule as legacy add_report: assignee (or unassigned), admin-level, or the two edit overrides."""
        if study.assignee_user_idx is None or int(study.assignee_user_idx) == user.user_idx:
            return True
        if self._can_act_on_others(user):
            return True
        return user.report_edit_all or (report is not None and report.open_edit == "Y")

    # ------------------------------------------------------------------ certificate login

    def _enrollment_user(self, session, manage_session, user_idx, *, doctor_required=False):
        if user_idx is None:
            raise VcError(401, "VC_LOGIN_REQUIRED", "PACS login is required")
        user = self.port.get_user(manage_session, int(user_idx))
        if user is None or not user.active or not user.pacs_user:
            raise VcError(403, "VC_LOCAL_USER_INACTIVE", "An active PACS account is required")
        if doctor_required:
            self._require_local_doctor(session, user, "VC_SELF_ENROLLMENT")
        return user

    @staticmethod
    def _enrollment_remote(action):
        try:
            return action()
        except VcUnavailable:
            raise VcError(503, "VC_UNAVAILABLE", "Certificate enrollment service is unavailable", retryable=True)
        except VcRejected as e:
            raise VcError(e.status, e.code, "Certificate enrollment request was rejected")

    def enrollment_status(self, session, manage_session, user_idx):
        user = self._enrollment_user(session, manage_session, user_idx)
        if not user.is_doctor:
            return {"doctorId": None, "registrationStatus": "NOT_REQUIRED",
                    "certificateEnrollmentId": None, "fingerprintSha256": None}
        self.require_operational()
        result = self._enrollment_remote(lambda: self.client.enrollment_status(
            self.config.tenant_id, str(user.user_idx)))
        state = result.get("registrationStatus")
        doctor_id = _uuid(result.get("doctorId"))
        if state not in {"NOT_REGISTERED", "PENDING", "ACTIVE", "SUSPENDED", "REVOKED"} or (
                state != "NOT_REGISTERED" and doctor_id is None):
            raise VcError(503, "VC_ENROLLMENT_RESPONSE_INVALID", "Invalid enrollment status", retryable=True)
        if state == "ACTIVE":
            enrollment_id = _uuid(result.get("certificateEnrollmentId"))
            fingerprint = result.get("fingerprintSha256")
            license_number = result.get("doctorLicenseNumber")
            if not enrollment_id or not isinstance(fingerprint, str) or not _HEX64.fullmatch(fingerprint):
                raise VcError(503, "VC_ENROLLMENT_RESPONSE_INVALID", "Invalid enrollment evidence", retryable=True)
            if (not isinstance(license_number, str) or not _LICENSE.fullmatch(license_number)
                    or _license_digits(user.doctor_license_number) != license_number):
                raise VcError(403, "VC_LICENSE_MISMATCH", "PACS and registered doctor licenses differ")
            self._link_self_enrollment(session, user, doctor_id, enrollment_id)
        return {"doctorId": doctor_id, "registrationStatus": state,
                "certificateEnrollmentId": result.get("certificateEnrollmentId"),
                "fingerprintSha256": result.get("fingerprintSha256")}

    def _link_self_enrollment(self, session, user, doctor_id, enrollment_id):
        """Only verified server status for this tenant/client/user can authorize this mapping. No reactivation."""
        links = session.execute(select(VcDoctorLink).where(
            (VcDoctorLink.user_idx == user.user_idx) |
            ((VcDoctorLink.vc_tenant_id == self.config.tenant_id) & (VcDoctorLink.vc_doctor_id == doctor_id))
        )).scalars().all()
        if links:
            if (len(links) == 1 and links[0].status == "ACTIVE" and links[0].user_idx == user.user_idx
                    and links[0].vc_tenant_id == self.config.tenant_id and links[0].vc_doctor_id == doctor_id):
                return
            raise VcError(409, "VC_LINK_CONFLICT", "Existing or revoked certificate mapping requires review")
        link = VcDoctorLink(user_idx=user.user_idx, vc_tenant_id=self.config.tenant_id, vc_doctor_id=doctor_id,
                            status="ACTIVE", active_user_idx=user.user_idx,
                            active_doctor_key=f"{self.config.tenant_id}:{doctor_id}",
                            evidence_ref=f"pacs-self-enrollment:{enrollment_id}",
                            note="Verified first-login certificate enrollment", created_by_user_idx=user.user_idx,
                            created_at=self.clock())
        try:
            session.add(link)
            session.flush()
            self._audit(session, user.user_idx, "VC_SELF_ENROLLMENT_LINKED", "VC_LINK", link.link_idx, "SUCCESS",
                        doctorId=doctor_id, enrollmentId=enrollment_id)
            session.commit()
        except IntegrityError:
            session.rollback()
            # A simultaneous identical completion is idempotent; other mappings remain denied.
            current = self._active_link_for_doctor(session, self.config.tenant_id, doctor_id)
            if current is None or current.status != "ACTIVE" or current.user_idx != user.user_idx:
                raise VcError(409, "VC_LINK_CONFLICT", "Conflicting certificate mapping")

    def enrollment_start(self, session, manage_session, user_idx):
        self.require_operational()
        user = self._enrollment_user(session, manage_session, user_idx, doctor_required=True)
        license_number = _license_digits(user.doctor_license_number)
        center_name = self.port.enrollment_center(manage_session, user.user_idx)
        if not _LICENSE.fullmatch(license_number) or not user.user_name.strip() or not center_name:
            raise VcError(409, "VC_PACS_PROFILE_INCOMPLETE", "PACS doctor name, license and center are required")
        result = self._enrollment_remote(lambda: self.client.enrollment_start({
            "tenantId": self.config.tenant_id, "pacsUserId": str(user.user_idx),
            "name": user.user_name.strip(), "licenseNumber": license_number, "centerName": center_name}))
        if not _uuid(result.get("doctorId")) or not isinstance(result.get("challenge"), dict):
            raise VcError(503, "VC_ENROLLMENT_RESPONSE_INVALID", "Invalid enrollment challenge", retryable=True)
        self._audit(session, user.user_idx, "VC_SELF_ENROLLMENT_STARTED", "VC_DOCTOR", result["doctorId"], "SUCCESS")
        session.commit()
        return {"doctorId": result["doctorId"], "challenge": result["challenge"]}

    def require_enrolled_pacs_access(self, session, manage_session, user_idx, authorization=None):
        if not self.config.guard_active:
            return
        # The certificate policy applies only to doctors. A misconfigured provider must not lock staff out of PACS.
        if user_idx is not None:
            user = self.port.get_user(manage_session, int(user_idx))
            if user is not None and user.active and user.pacs_user and not user.is_doctor:
                return
        # Enrollment is not login authentication. Each physician request needs a live certificate session;
        # a cached/password JWT, client flags or a remembered ACTIVE enrollment cannot satisfy this check.
        principal = self.resolve_session(session, manage_session, authorization)
        if principal.user_idx != user_idx:
            raise VcError(401, "VC_SESSION_REQUIRED", "Certificate session does not match the PACS account")
        result = self.enrollment_status(session, manage_session, user_idx)
        if result["registrationStatus"] != "ACTIVE":
            raise VcError(403, "VC_ENROLLMENT_REQUIRED", "Complete first-login certificate registration",
                          data={"registrationStatus": result["registrationStatus"]})
        if (result["doctorId"] != principal.doctor_id
                or result["certificateEnrollmentId"] != principal.enrollment_id
                or result["fingerprintSha256"] != principal.certificate_fingerprint):
            raise VcError(401, "VC_SESSION_REQUIRED", "Registered certificate changed; log in with it again")

    def certificate_login(self, session: Session, manage_session: Session, handoff_code, client_ip: str | None):
        self.require_operational()
        identity, link = self._redeem_linked_identity(session, handoff_code, "VC_LOGIN")
        user = self.port.get_user(manage_session, link.user_idx)
        self._require_local_doctor(session, user, "VC_LOGIN")
        self._require_license_match(session, user, identity, "VC_LOGIN")
        return self._issue_session(session, identity, link, user, client_ip, "VC_LOGIN")

    def certificate_step_up(self, session: Session, manage_session: Session, legacy_user_idx, handoff_code,
                            client_ip: str | None):
        """Password-logged-in PACS user proves the same identity with a certificate. The code is redeemed only for an
        authenticated legacy user, and a session is issued only when the linked PACS user is that same user."""
        self.require_operational()
        if legacy_user_idx is None:
            raise VcError(401, "VC_LOGIN_REQUIRED", "PACS에 먼저 로그인해야 인증서 확인을 할 수 있습니다.")
        legacy_user_idx = int(legacy_user_idx)
        identity, link = self._redeem_linked_identity(session, handoff_code, "VC_STEP_UP")
        if link.user_idx != legacy_user_idx:
            self._audit(session, legacy_user_idx, "VC_STEP_UP", "VC_DOCTOR", identity.doctor_id, "DENIED",
                        code="IDENTITY_MISMATCH")
            session.commit()
            raise VcError(403, "VC_STEP_UP_IDENTITY_MISMATCH",
                          "이 인증서는 현재 로그인한 PACS 계정과 연결된 의사의 인증서가 아닙니다.")
        user = self.port.get_user(manage_session, link.user_idx)
        self._require_local_doctor(session, user, "VC_STEP_UP")
        self._require_license_match(session, user, identity, "VC_STEP_UP")
        return self._issue_session(session, identity, link, user, client_ip, "VC_STEP_UP")

    def _redeem_linked_identity(self, session: Session, handoff_code, action: str):
        """One-use server-to-server redeem, identity validation and the ACTIVE admin link for that doctor."""
        if not isinstance(handoff_code, str) or not _HANDOFF.match(handoff_code):
            raise VcError(401, "VC_HANDOFF_INVALID", "인증서 로그인 정보가 올바르지 않습니다. 다시 로그인해 주세요.")
        try:
            raw = self.client.redeem_handoff(handoff_code)
        except VcRejected as e:
            self._audit(session, None, action, "HANDOFF", None, "DENIED", code=e.code)
            session.commit()
            raise VcError(401, "VC_HANDOFF_INVALID", "인증서 로그인 정보가 올바르지 않거나 만료되었습니다.")
        except VcUnavailable:
            raise VcError(503, "VC_UNAVAILABLE", "Vision-Certi에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                          retryable=True)
        identity = self._verified_identity(raw)
        if identity is None:
            self._audit(session, None, action, "HANDOFF", None, "DENIED", code="IDENTITY_REJECTED")
            session.commit()
            raise VcError(401, "VC_HANDOFF_INVALID", "인증서 로그인 결과를 확인할 수 없습니다.")
        link = self._active_link_for_doctor(session, identity.tenant_id, identity.doctor_id)
        if link is None:
            self._audit(session, None, action, "VC_DOCTOR", identity.doctor_id, "DENIED", code="NOT_LINKED")
            session.commit()
            raise VcError(403, "VC_DOCTOR_NOT_LINKED",
                          "이 인증서 의사 계정이 PACS 사용자와 연결되어 있지 않습니다. 관리자에게 연결을 요청하세요.")
        return identity, link

    def _verified_identity(self, identity) -> Optional["VerifiedIdentity"]:
        now = self.clock()
        try:
            tenant_id = identity["tenantId"]
            doctor_id = _uuid(identity["doctorId"])
            enrollment_id = _uuid(identity["certificateEnrollmentId"])
            fingerprint = identity["certificateFingerprintSha256"]
            mode = identity["identityMode"]
            vc_expires = _parse_instant(identity["sessionExpiresAt"])
            authenticated_at = _parse_instant(identity["authenticatedAt"])
            license_number = identity.get("doctorLicenseNumber")
            valid = (tenant_id == self.config.tenant_id and doctor_id and enrollment_id
                     and isinstance(fingerprint, str) and _HEX64.match(fingerprint) and mode in _CERT_MODES
                     and identity.get("activeSigner") is True and identity.get("doctorStatus") == "ACTIVE"
                     and vc_expires > now)
        except (KeyError, TypeError, ValueError, AttributeError):
            valid = False
        if not valid:
            return None
        return VerifiedIdentity(tenant_id, doctor_id, enrollment_id, fingerprint, mode, authenticated_at, vc_expires,
                                license_number)

    def _require_license_match(self, session: Session, user: LocalUser, identity: "VerifiedIdentity",
                               action: str) -> None:
        """Required deny condition, never an authenticator: Vision-Certi must report the admin-verified license number
        and the local PACS doctor's license must be that number. Absent or malformed fails closed."""
        expected = identity.license_number.strip() if isinstance(identity.license_number, str) else ""
        if not _LICENSE.match(expected) or _license_digits(user.doctor_license_number) != expected:
            self._audit(session, user.user_idx, action, "USER", user.user_idx, "DENIED", code="LICENSE_MISMATCH",
                        doctorId=identity.doctor_id)
            session.commit()
            raise VcError(403, "VC_LICENSE_MISMATCH",
                          "PACS 계정의 의사면허번호가 Vision-Certi에 등록된 면허번호와 다릅니다. 관리자에게 확인을 요청하세요.")

    def _issue_session(self, session: Session, identity: "VerifiedIdentity", link: VcDoctorLink, user: LocalUser,
                       client_ip: str | None, action: str):
        now = self.clock()
        token = TOKEN_PREFIX + secrets.token_urlsafe(32)
        expires_at = min(now + datetime.timedelta(minutes=self.config.session_max_minutes), identity.session_expires_at)
        row = VcSession(token_sha256=token_sha256(token), user_idx=user.user_idx, link_idx=link.link_idx,
                        vc_tenant_id=identity.tenant_id, vc_doctor_id=identity.doctor_id,
                        vc_enrollment_id=identity.enrollment_id,
                        vc_certificate_fingerprint=identity.certificate_fingerprint,
                        vc_identity_mode=identity.identity_mode, vc_authenticated_at=identity.authenticated_at,
                        vc_session_expires_at=identity.session_expires_at, created_at=now,
                        expires_at=expires_at, client_ip=(client_ip or "")[:64] or None)
        session.add(row)
        session.flush()
        self._audit(session, user.user_idx, action, "VC_SESSION", row.session_idx, "SUCCESS",
                    doctorId=identity.doctor_id, enrollmentId=identity.enrollment_id, licenseChecked=True)
        session.commit()
        return token, row, user

    def _require_local_doctor(self, session: Session, user: Optional[LocalUser], action: str) -> LocalUser:
        if user is None or not user.active:
            self._audit(session, None if user is None else user.user_idx, action, "USER",
                        None if user is None else user.user_idx, "DENIED", code="LOCAL_USER_INACTIVE")
            session.commit()
            raise VcError(403, "VC_LOCAL_USER_INACTIVE", "PACS 사용자 계정이 비활성(차단) 상태입니다.")
        if not user.is_doctor or not user.pacs_user:
            self._audit(session, user.user_idx, action, "USER", user.user_idx, "DENIED", code="NOT_DOCTOR")
            session.commit()
            raise VcError(403, "VC_LOCAL_USER_NOT_DOCTOR", "판독의(의사) PACS 계정이 아닙니다.")
        return user

    def resolve_session(self, session: Session, manage_session: Session, authorization: str | None) -> VcPrincipal:
        """Fresh check on every request (no cache): session, link, local user and doctor permission."""
        self.require_operational()
        token = (authorization or "")
        if token.startswith("Bearer "):
            token = token[len("Bearer "):].strip()
        if not _TOKEN.match(token):
            raise VcError(401, "VC_SESSION_REQUIRED", "인증서 로그인이 필요합니다.")
        row = session.execute(select(VcSession).where(VcSession.token_sha256 == token_sha256(token))).scalar_one_or_none()
        now = self.clock()
        if row is None or row.revoked_at is not None or not now < row.expires_at:
            raise VcError(401, "VC_SESSION_REQUIRED", "인증서 로그인이 필요하거나 세션이 만료되었습니다.")
        link = session.get(VcDoctorLink, row.link_idx)
        if (link is None or link.status != "ACTIVE" or link.user_idx != row.user_idx
                or link.vc_doctor_id != row.vc_doctor_id or link.vc_tenant_id != row.vc_tenant_id):
            raise VcError(401, "VC_SESSION_REQUIRED", "인증서 연결이 해제되었습니다. 다시 로그인해 주세요.")
        user = self.port.get_user(manage_session, row.user_idx)
        if user is None or not user.active or not user.is_doctor or not user.pacs_user:
            raise VcError(403, "VC_LOCAL_USER_INACTIVE", "PACS 사용자 계정이 비활성 상태이거나 판독의가 아닙니다.")
        return VcPrincipal(row.session_idx, row.user_idx, row.link_idx, row.vc_tenant_id, row.vc_doctor_id,
                           row.vc_enrollment_id, row.vc_certificate_fingerprint, row.vc_identity_mode,
                           row.expires_at, user)

    def logout(self, session: Session, principal: VcPrincipal) -> None:
        row = session.get(VcSession, principal.session_idx)
        if row is not None and row.revoked_at is None:
            row.revoked_at = self.clock()
            row.revoke_reason = "LOGOUT"
            self._audit(session, principal.user_idx, "VC_LOGOUT", "VC_SESSION", row.session_idx, "SUCCESS")
            session.commit()

    @staticmethod
    def session_view(principal: VcPrincipal) -> dict:
        return {"userIdx": principal.user_idx, "tenantId": principal.tenant_id, "doctorId": principal.doctor_id,
                "certificateEnrollmentId": principal.enrollment_id,
                "certificateFingerprintSha256": principal.certificate_fingerprint,
                "identityMode": principal.identity_mode, "expiresAt": principal.expires_at.isoformat() + "Z"}

    # ------------------------------------------------------------------ explicit doctor links (admin)

    def _active_link_for_doctor(self, session: Session, tenant_id: str, doctor_id: str) -> Optional[VcDoctorLink]:
        return session.execute(select(VcDoctorLink).where(
            VcDoctorLink.active_doctor_key == f"{tenant_id}:{doctor_id}")).scalar_one_or_none()

    def _require_link_admin(self, session: Session, manage_session: Session, admin_user_idx) -> LocalUser:
        self.require_operational()
        admin = self.port.get_user(manage_session, admin_user_idx) if admin_user_idx is not None else None
        if admin is None or not admin.active or not self.is_link_admin(admin.user_idx):
            self._audit(session, admin_user_idx, "VC_LINK_ADMIN", "USER", admin_user_idx, "DENIED")
            session.commit()
            raise VcError(403, "VC_LINK_ADMIN_REQUIRED", "인증서 의사 연결은 지정된 관리자만 할 수 있습니다.")
        return admin

    def list_links(self, session: Session, manage_session: Session, admin_user_idx) -> list[dict]:
        self._require_link_admin(session, manage_session, admin_user_idx)
        rows = session.execute(select(VcDoctorLink).order_by(VcDoctorLink.link_idx.desc())).scalars().all()
        return [self.link_view(r) for r in rows]

    @staticmethod
    def link_view(r: VcDoctorLink) -> dict:
        return {"linkIdx": r.link_idx, "userIdx": r.user_idx, "vcTenantId": r.vc_tenant_id,
                "vcDoctorId": r.vc_doctor_id, "status": r.status, "evidenceRef": r.evidence_ref, "note": r.note,
                "createdByUserIdx": r.created_by_user_idx, "createdAt": r.created_at.isoformat() + "Z",
                "revokedByUserIdx": r.revoked_by_user_idx,
                "revokedAt": r.revoked_at.isoformat() + "Z" if r.revoked_at else None,
                "revokeReason": r.revoke_reason}

    def create_link(self, session: Session, manage_session: Session, admin_user_idx, body: dict) -> dict:
        admin = self._require_link_admin(session, manage_session, admin_user_idx)
        try:
            user_idx = int(body.get("user_idx"))
        except (TypeError, ValueError):
            raise VcError(422, "VC_VALIDATION_FAILED", "user_idx is required")
        tenant_id = body.get("vc_tenant_id")
        doctor_id = _uuid(body.get("vc_doctor_id"))
        evidence_ref = (body.get("evidence_ref") or "").strip()
        note = (body.get("note") or "").strip() or None
        if tenant_id != self.config.tenant_id:
            raise VcError(422, "VC_TENANT_NOT_CONFIGURED", "vc_tenant_id must be the configured Vision-Certi tenant")
        if doctor_id is None or not evidence_ref or len(evidence_ref) > 255 or (note and len(note) > 500):
            raise VcError(422, "VC_VALIDATION_FAILED", "vc_doctor_id (UUID) and evidence_ref (1-255) are required")
        user = self.port.get_user(manage_session, user_idx)
        if user is None or not user.active:
            raise VcError(422, "VC_LOCAL_USER_INACTIVE", "user_idx is not an active PACS user")
        if not user.is_doctor or not user.pacs_user:
            raise VcError(422, "VC_LOCAL_USER_NOT_DOCTOR", "user_idx is not a PACS doctor account")
        link = VcDoctorLink(user_idx=user_idx, vc_tenant_id=tenant_id, vc_doctor_id=doctor_id, status="ACTIVE",
                            active_user_idx=user_idx, active_doctor_key=f"{tenant_id}:{doctor_id}",
                            evidence_ref=evidence_ref, note=note, created_by_user_idx=admin.user_idx,
                            created_at=self.clock())
        session.add(link)
        try:
            session.flush()
        except IntegrityError:
            session.rollback()
            raise VcError(409, "VC_LINK_CONFLICT", "이 PACS 사용자 또는 Vision-Certi 의사는 이미 활성 연결이 있습니다.")
        self._audit(session, admin.user_idx, "VC_LINK_CREATED", "VC_LINK", link.link_idx, "SUCCESS",
                    userIdx=user_idx, doctorId=doctor_id, evidenceRef=evidence_ref)
        session.commit()
        return self.link_view(link)

    def revoke_link(self, session: Session, manage_session: Session, admin_user_idx, link_idx: int,
                    reason: str) -> dict:
        admin = self._require_link_admin(session, manage_session, admin_user_idx)
        link = session.get(VcDoctorLink, int(link_idx))
        if link is None:
            raise VcError(404, "VC_LINK_NOT_FOUND", "link not found")
        reason = (reason or "").strip()
        if not reason or len(reason) > 255:
            raise VcError(422, "VC_VALIDATION_FAILED", "reason (1-255) is required")
        if link.status == "ACTIVE":
            now = self.clock()
            link.status = "REVOKED"
            link.active_user_idx = None
            link.active_doctor_key = None
            link.revoked_at = now
            link.revoked_by_user_idx = admin.user_idx
            link.revoke_reason = reason
            for s in session.execute(select(VcSession).where(VcSession.link_idx == link.link_idx,
                                                             VcSession.revoked_at.is_(None))).scalars():
                s.revoked_at = now
                s.revoke_reason = "LINK_REVOKED"
            self._audit(session, admin.user_idx, "VC_LINK_REVOKED", "VC_LINK", link.link_idx, "SUCCESS")
            session.commit()
        return self.link_view(link)

    # ------------------------------------------------------------------ report approval

    def _approvals_for_report(self, session: Session, report_idx: int) -> list[VcReportApproval]:
        return list(session.execute(select(VcReportApproval).where(VcReportApproval.report_idx == report_idx)
                                    .order_by(VcReportApproval.revision.desc())).scalars())

    def current_report(self, session: Session, principal: VcPrincipal, study_idx: int) -> dict:
        """Fetch saved content and its canonical hash for the prepare precondition, under doctor permissions."""
        self.require_operational()
        study = self.port.get_study(session, study_idx)
        report = self.port.get_report_for_study(session, study_idx)
        if study is None or report is None:
            raise VcError(404, "VC_REPORT_NOT_FOUND", "저장된 판독문이 없습니다.")
        if not self._may_write_report(principal.user, study, report):
            raise VcError(403, "VC_REPORT_ACCESS_DENIED", "이 판독문의 승인 권한이 없습니다.")
        return {"contentSha256": snap.content_sha256(report),
                "report": {"reportId": report.report_idx, "studyId": report.study_idx,
                           "reading": report.reading, "conclusion": report.conclusion}}

    def prepare(self, session: Session, manage_session: Session, principal: VcPrincipal, study_idx: int,
                expected_content_sha256) -> tuple[VcReportApproval, int]:
        self.require_operational()
        study = self.port.get_study(session, study_idx)
        if study is None:
            raise VcError(404, "VC_STUDY_NOT_FOUND", "study does not exist")
        report = self.port.get_report_for_study(session, study_idx)
        if report is None:
            raise VcError(422, "VC_REPORT_NOT_FOUND", "판독문을 먼저 저장한 뒤 승인할 수 있습니다.")
        user = principal.user
        if not self._may_write_report(user, study, report):
            self._audit(session, user.user_idx, "VC_PREPARE", "REPORT", report.report_idx, "DENIED",
                        code="NOT_ASSIGNEE")
            session.commit()
            raise VcError(409, "VC_NOT_ASSIGNEE", "이 검사는 다른 판독의에게 배정되었습니다. 목록을 새로고침해주세요.")
        if not (report.reading or "").strip() and not (report.conclusion or "").strip():
            raise VcError(422, "VC_REPORT_EMPTY", "빈 판독문은 승인할 수 없습니다.")
        content_sha = snap.content_sha256(report)
        if not isinstance(expected_content_sha256, str) or expected_content_sha256 != content_sha:
            raise VcError(409, "VC_REPORT_CHANGED", "화면의 판독문이 저장된 내용과 다릅니다. 저장 후 다시 시도하세요.",
                          data={"currentContentSha256": content_sha})
        approvals = self._approvals_for_report(session, report.report_idx)
        approved = [a for a in approvals if a.state == APPROVED]
        if approved and approved[0].content_sha256 == content_sha and report.status in ("A", "RA"):
            raise VcError(409, "VC_ALREADY_APPROVED", "이미 인증서 서명으로 승인된 판독문입니다.",
                          data={"approvalId": approved[0].approval_id})
        if any(a.state == SIGNED_REMOTE for a in approvals):
            in_progress = next(a for a in approvals if a.state == SIGNED_REMOTE)
            raise VcError(409, "VC_APPROVAL_IN_PROGRESS", "서명이 완료된 승인 처리가 남아 있습니다. 먼저 완료(finalize)하세요.",
                          data={"approvalId": in_progress.approval_id})
        now = self.clock()
        # Idempotent: the same doctor re-preparing the same content reuses the open approval (same idempotency key).
        for a in approvals:
            if a.state in OPEN_STATES and a.content_sha256 == content_sha and a.user_idx == user.user_idx \
                    and (a.vc_request_expires_at is None or a.vc_request_expires_at > now):
                if a.state == PENDING_SIGNATURE:
                    return a, 200
                return self._send_prepare(session, a)
        for a in approvals:
            if a.state in OPEN_STATES:
                a.state = SUPERSEDED
                a.state_reason = "NEW_PREPARE"
                a.updated_at = now
        revision = (approvals[0].revision + 1) if approvals else 1
        target = "RA" if approved or report.status in ("RR", "RA") else "A"
        approval_id = str(uuid.uuid4())
        snapshot_bytes = snap.build_snapshot(
            client_id=self.config.client_id, tenant_id=principal.tenant_id, doctor_id=principal.doctor_id,
            revision=revision, target_status=target, report=report, study=study, user=user, content_sha=content_sha,
            supersedes_approval_id=approved[0].approval_id if approved else None,
            prior_signed_snapshot_sha256=approved[0].snapshot_sha256 if approved else None)
        approval = VcReportApproval(
            approval_id=approval_id, study_idx=study.study_idx, report_idx=report.report_idx, revision=revision,
            target_status=target, user_idx=user.user_idx, link_idx=principal.link_idx,
            session_idx=principal.session_idx, vc_tenant_id=principal.tenant_id, vc_doctor_id=principal.doctor_id,
            content_sha256=content_sha, snapshot_json=snapshot_bytes.decode("utf-8"),
            snapshot_sha256=snap.sha256_hex(snapshot_bytes), state=PREPARING, attempt_count=0,
            supersedes_approval_id=approved[0].approval_id if approved else None, created_at=now, updated_at=now)
        session.add(approval)
        try:
            session.flush()
        except IntegrityError:  # concurrent prepare took the same revision
            session.rollback()
            raise VcError(409, "VC_CONCURRENT_PREPARE", "다른 승인 준비가 진행 중입니다. 다시 시도하세요.", retryable=True)
        self._audit(session, user.user_idx, "VC_PREPARE", "VC_APPROVAL", approval_id, "SUCCESS",
                    reportIdx=report.report_idx, revision=revision, snapshotSha256=approval.snapshot_sha256)
        session.commit()  # persisted before any remote call
        return self._send_prepare(session, approval)

    def _send_prepare(self, session: Session, approval: VcReportApproval) -> tuple[VcReportApproval, int]:
        snapshot_bytes = approval.snapshot_json.encode("utf-8")
        payload = {
            "tenantId": approval.vc_tenant_id, "doctorId": approval.vc_doctor_id,
            "externalReportId": snap.external_report_id(self.config.client_id, approval.report_idx),
            "externalStudyId": snap.external_study_id(self.config.client_id, approval.study_idx),
            "revision": approval.revision,
            "title": f"PACS report {approval.report_idx} rev {approval.revision}",
            "contentType": snap.CONTENT_TYPE,
            "contentBase64": base64.b64encode(snapshot_bytes).decode("ascii"),
            "contentSha256": approval.snapshot_sha256,
        }
        approval.attempt_count = (approval.attempt_count or 0) + 1
        now = self.clock()
        try:
            created = self.client.create_report_sign_request(payload, f"pacs-approval-{approval.approval_id}")
        except VcUnavailable as e:
            approval.state = PREPARE_RETRY
            approval.last_error_code = e.reason[:64]
            approval.updated_at = now
            session.commit()
            raise VcError(503, "VC_UNAVAILABLE", "Vision-Certi에 연결할 수 없습니다. 같은 요청으로 다시 시도하세요.",
                          retryable=True, data={"approval": self.approval_view(approval)})
        except VcRejected as e:
            approval.state = REMOTE_FAILED
            approval.last_error_code = e.code[:64]
            approval.state_reason = "SIGN_REQUEST_REJECTED"
            approval.updated_at = now
            session.commit()
            raise VcError(502 if e.status in (401, 403) else 409, "VC_SIGN_REQUEST_REJECTED",
                          "Vision-Certi가 서명 요청을 거부했습니다.", data={"visionCertiCode": e.code,
                                                                    "approval": self.approval_view(approval)})
        sign_request_id = _uuid(created.get("signRequestId"))
        if (sign_request_id is None or created.get("documentSha256") != approval.snapshot_sha256
                or created.get("signerId") != approval.vc_doctor_id):
            approval.state = REMOTE_FAILED
            approval.state_reason = "SIGN_REQUEST_BINDING_MISMATCH"
            approval.updated_at = now
            self._audit(session, approval.user_idx, "VC_PREPARE", "VC_APPROVAL", approval.approval_id, "ERROR",
                        code="BINDING_MISMATCH")
            session.commit()
            raise VcError(502, "VC_BINDING_MISMATCH", "Vision-Certi 응답이 준비한 판독문과 일치하지 않습니다.")
        approval.vc_sign_request_id = sign_request_id
        approval.vc_document_version_id = _uuid(created.get("documentVersionId"))
        try:
            approval.vc_request_expires_at = _parse_instant(created.get("expiresAt"))
        except ValueError:
            approval.vc_request_expires_at = None
        approval.state = PENDING_SIGNATURE
        approval.last_error_code = None
        approval.updated_at = now
        session.commit()
        return approval, 201

    def _load_approval(self, session: Session, approval_id) -> VcReportApproval:
        canonical = _uuid(approval_id)
        row = None if canonical is None else session.execute(
            select(VcReportApproval).where(VcReportApproval.approval_id == canonical)).scalar_one_or_none()
        if row is None:
            raise VcError(404, "VC_APPROVAL_NOT_FOUND", "approval not found")
        return row

    def finalize(self, session: Session, manage_session: Session, approval_id, *, principal: VcPrincipal | None = None,
                 admin_user_idx=None) -> tuple[VcReportApproval, int]:
        self.require_operational()
        approval = self._load_approval(session, approval_id)
        actor = None
        if principal is not None:
            if principal.user_idx != approval.user_idx or principal.doctor_id != approval.vc_doctor_id:
                raise VcError(403, "VC_NOT_APPROVAL_OWNER", "본인이 준비한 승인만 완료할 수 있습니다.")
            actor = principal.user_idx
        else:
            actor = self._require_link_admin(session, manage_session, admin_user_idx).user_idx
        return self._advance(session, manage_session, approval, actor)

    def _advance(self, session: Session, manage_session: Session, approval: VcReportApproval, actor):
        if approval.state == APPROVED:
            return approval, 200
        if approval.state in (EXPIRED, REMOTE_FAILED, SUPERSEDED, SUPERSEDED_AFTER_SIGNATURE, COMMIT_REFUSED):
            raise VcError(409, "VC_APPROVAL_CLOSED", "이 승인은 종료되었습니다. 새로 승인을 준비하세요.",
                          data={"approval": self.approval_view(approval)})
        if approval.state in (PREPARING, PREPARE_RETRY):
            approval, _ = self._send_prepare(session, approval)
        if approval.state == PENDING_SIGNATURE:
            self._pull_evidence(session, approval)
        if approval.state == SIGNED_REMOTE:
            return self._commit_local(session, manage_session, approval, actor)
        raise VcError(409, "VC_APPROVAL_CLOSED", "이 승인은 종료되었습니다.", data={"approval": self.approval_view(approval)})

    def _pull_evidence(self, session: Session, approval: VcReportApproval) -> None:
        now = self.clock()
        try:
            status = self.client.get_report_sign_request(approval.vc_sign_request_id)
        except VcUnavailable as e:
            approval.attempt_count = (approval.attempt_count or 0) + 1
            approval.last_error_code = e.reason[:64]
            approval.updated_at = now
            session.commit()
            raise VcError(503, "VC_UNAVAILABLE", "서명 결과를 확인하지 못했습니다. 승인되지 않았으며 다시 시도할 수 있습니다.",
                          retryable=True, data={"approval": self.approval_view(approval)})
        except VcRejected as e:
            approval.state = REMOTE_FAILED
            approval.state_reason = "SIGN_REQUEST_LOOKUP_REJECTED"
            approval.last_error_code = e.code[:64]
            approval.updated_at = now
            session.commit()
            raise VcError(409, "VC_APPROVAL_CLOSED", "Vision-Certi에서 서명 요청을 찾을 수 없습니다.",
                          data={"approval": self.approval_view(approval)})
        remote = status.get("status")
        if remote in ("PENDING_APPROVAL", "APPROVED", "SIGNING", "EVIDENCE_PENDING"):
            raise VcError(409, "VC_NOT_YET_SIGNED", "의사가 아직 Vision-Certi에서 서명하지 않았습니다.",
                          retryable=True, data={"approval": self.approval_view(approval), "remoteStatus": remote})
        if remote == "EXPIRED":
            approval.state = EXPIRED
            approval.updated_at = now
            session.commit()
            raise VcError(409, "VC_APPROVAL_CLOSED", "서명 요청이 만료되었습니다. 새로 승인을 준비하세요.",
                          data={"approval": self.approval_view(approval)})
        if remote != "COMPLETED":
            approval.state = REMOTE_FAILED
            approval.state_reason = f"REMOTE_{remote}"[:255]
            approval.updated_at = now
            session.commit()
            raise VcError(409, "VC_APPROVAL_CLOSED", "Vision-Certi 서명이 실패했습니다.",
                          data={"approval": self.approval_view(approval)})
        problem = self._evidence_problem(approval, status)
        if problem:
            approval.state = REMOTE_FAILED
            approval.state_reason = f"EVIDENCE_INVALID:{problem}"[:255]
            approval.updated_at = now
            self._audit(session, approval.user_idx, "VC_EVIDENCE", "VC_APPROVAL", approval.approval_id, "ERROR",
                        problem=problem)
            session.commit()
            raise VcError(502, "VC_EVIDENCE_INVALID", "서명 증거가 준비한 판독문과 일치하지 않아 승인하지 않았습니다.",
                          data={"approval": self.approval_view(approval)})
        sig = status["signature"]
        ev = sig["doctorEvidence"]
        # Evidence columns are written exactly once (state only advances past PENDING_SIGNATURE here).
        approval.vc_signature_id = _uuid(sig.get("signatureId"))
        approval.vc_signature_algorithm = sig.get("algorithm")
        approval.vc_signature_mode = sig.get("signatureMode")
        approval.vc_signature_value = sig.get("signatureValueBase64")
        try:
            approval.vc_signed_at = _parse_instant(sig.get("signedAt"))
        except ValueError:
            approval.vc_signed_at = None
        approval.vc_certificate_enrollment_id = _uuid(ev.get("certificateEnrollmentId"))
        approval.vc_certificate_fingerprint = ev.get("certificateEnrollmentFingerprint")
        approval.vc_certificate_serial = (ev.get("certificateId") or "")[:128] or None
        approval.vc_key_name = (ev.get("keyName") or "")[:255] or None
        approval.vc_key_version = ev.get("keyVersion") if isinstance(ev.get("keyVersion"), int) else None
        approval.vc_public_key_spki = ev.get("publicKeySpki")
        approval.vc_evidence_json = json.dumps(status, ensure_ascii=False, sort_keys=True)
        approval.state = SIGNED_REMOTE
        approval.updated_at = now
        self._audit(session, approval.user_idx, "VC_SIGNED_REMOTE", "VC_APPROVAL", approval.approval_id, "SUCCESS",
                    signatureId=approval.vc_signature_id)
        session.commit()

    def _evidence_problem(self, approval: VcReportApproval, status: dict) -> Optional[str]:
        sig = status.get("signature")
        if not isinstance(sig, dict) or not isinstance(sig.get("doctorEvidence"), dict):
            return "NO_SIGNATURE"
        ev = sig["doctorEvidence"]
        checks = {
            "SIGN_REQUEST": _uuid(status.get("signRequestId")) == approval.vc_sign_request_id,
            "TENANT": status.get("tenantId") == approval.vc_tenant_id,
            "SIGNER": status.get("signerId") == approval.vc_doctor_id and ev.get("doctorId") == approval.vc_doctor_id,
            "DOCUMENT_HASH": status.get("documentSha256") == approval.snapshot_sha256
            and sig.get("documentSha256") == approval.snapshot_sha256,
            "ALGORITHM": sig.get("algorithm") == "ECDSA_P256_SHA256",
            "REAL_SIGNER": ev.get("signingBackend") == "OPENBAO_TRANSIT"
            and ev.get("cryptographicallyAttributable") is True
            and ev.get("revocationCheckedAtSigning") is True
            and ev.get("identityMode") in _CERT_MODES
            and ev.get("certificateKind") == "X509",
            "SIGNATURE_ID": _uuid(sig.get("signatureId")) is not None,
            "ENROLLMENT": _uuid(ev.get("certificateEnrollmentId")) is not None
            and isinstance(ev.get("certificateEnrollmentFingerprint"), str)
            and bool(_HEX64.match(ev.get("certificateEnrollmentFingerprint"))),
        }
        for name, ok in checks.items():
            if not ok:
                return name
        try:
            _parse_instant(sig.get("signedAt"))
        except (ValueError, TypeError):
            return "SIGNATURE_TIME"
        try:
            ecdsa_p256.verify(ev.get("publicKeySpki") or "", approval.snapshot_json.encode("utf-8"),
                              sig.get("signatureValueBase64") or "")
        except ecdsa_p256.InvalidSignature:
            return "SIGNATURE"
        return None

    def _commit_local(self, session: Session, manage_session: Session, approval: VcReportApproval, actor):
        now = self.clock()
        approval_id = approval.approval_id
        try:
            report = self.port.get_report_for_study(session, approval.study_idx, for_update=True)
            refusal = None
            user = self.port.get_user(manage_session, approval.user_idx)
            link = session.get(VcDoctorLink, approval.link_idx)
            study = self.port.get_study(session, approval.study_idx)
            cert_session = session.get(VcSession, approval.session_idx)
            if user is None or not user.active or not user.is_doctor or not user.pacs_user:
                refusal = (COMMIT_REFUSED, "LOCAL_USER_INACTIVE")
            elif link is None or link.status != "ACTIVE" or link.user_idx != approval.user_idx:
                refusal = (COMMIT_REFUSED, "LINK_REVOKED")
            elif (cert_session is None or cert_session.user_idx != approval.user_idx
                  or cert_session.vc_doctor_id != approval.vc_doctor_id
                  or cert_session.vc_enrollment_id != approval.vc_certificate_enrollment_id):
                # The signing certificate must be the one proved by the login/step-up that prepared this approval.
                refusal = (COMMIT_REFUSED, "CERTIFICATE_CHANGED")
            elif report is None or report.report_idx != approval.report_idx or study is None:
                refusal = (SUPERSEDED_AFTER_SIGNATURE, "REPORT_MISSING")
            elif snap.content_sha256(report) != approval.content_sha256:
                refusal = (SUPERSEDED_AFTER_SIGNATURE, "REPORT_CHANGED_AFTER_SIGNATURE")
            elif not self._may_write_report(user, study, report):
                refusal = (COMMIT_REFUSED, "NOT_ASSIGNEE")
            else:
                newer = session.execute(select(func.count()).select_from(VcReportApproval).where(
                    VcReportApproval.report_idx == approval.report_idx,
                    VcReportApproval.revision > approval.revision,
                    VcReportApproval.state == APPROVED)).scalar()
                if newer:
                    refusal = (SUPERSEDED_AFTER_SIGNATURE, "NEWER_REVISION_APPROVED")
            if refusal is not None:
                approval.state, approval.state_reason = refusal
                approval.updated_at = now
                self._audit(session, actor, "VC_COMMIT", "VC_APPROVAL", approval_id, "DENIED", reason=refusal[1])
                session.commit()
                code = "VC_REPORT_CHANGED_AFTER_SIGNATURE" if refusal[0] == SUPERSEDED_AFTER_SIGNATURE \
                    else "VC_COMMIT_REFUSED"
                raise VcError(409, code, "서명은 보존되었지만 PACS 판독문을 승인 상태로 바꾸지 않았습니다.",
                              data={"approval": self.approval_view(approval), "reason": refusal[1]})
            self.port.apply_approval(session, approval.report_idx, approval.target_status, approval.user_idx, now)
            approval.state = APPROVED
            approval.committed_at = now
            approval.updated_at = now
            approval.last_error_code = None
            self._audit(session, actor, "VC_COMMIT", "VC_APPROVAL", approval_id, "SUCCESS",
                        status=approval.target_status, revision=approval.revision)
            session.commit()
            return approval, 200
        except VcError:
            raise
        except Exception:
            # Remote signature exists but the local write failed: keep SIGNED_REMOTE (retryable), never APPROVED.
            session.rollback()
            approval = self._load_approval(session, approval_id)
            approval.attempt_count = (approval.attempt_count or 0) + 1
            approval.last_error_code = "LOCAL_COMMIT_FAILED"
            approval.updated_at = self.clock()
            session.commit()
            raise VcError(503, "VC_LOCAL_COMMIT_FAILED", "서명은 완료되었으나 PACS 저장에 실패했습니다. 다시 시도하면 이어서 처리합니다.",
                          retryable=True, data={"approval": self.approval_view(approval)})

    def reconcile(self, session: Session, manage_session: Session, admin_user_idx, limit: int = 50) -> list[dict]:
        admin = self._require_link_admin(session, manage_session, admin_user_idx)
        rows = list(session.execute(select(VcReportApproval).where(VcReportApproval.state.in_(RECONCILE_STATES))
                                    .order_by(VcReportApproval.approval_idx).limit(max(1, min(limit, 200)))).scalars())
        results = []
        for row in rows:
            approval_id = row.approval_id
            try:
                approval, _ = self._advance(session, manage_session, row, admin.user_idx)
                results.append({"approvalId": approval_id, "state": approval.state, "code": None})
            except VcError as e:
                results.append({"approvalId": approval_id, "state": self._load_approval(session, approval_id).state,
                                "code": e.code})
        return results

    # ------------------------------------------------------------------ evidence views

    def require_reader(self, session: Session, manage_session: Session, user_idx, approval_or_study_idx) -> LocalUser:
        user = self.port.get_user(manage_session, user_idx) if user_idx is not None else None
        if user is None or not user.active:
            raise VcError(401, "VC_LOGIN_REQUIRED", "로그인이 필요합니다.")
        study = self.port.get_study(session, approval_or_study_idx)
        if study is None:
            raise VcError(404, "VC_STUDY_NOT_FOUND", "study does not exist")
        report = self.port.get_report_for_study(session, approval_or_study_idx)
        allowed = (self._can_act_on_others(user) or self.is_link_admin(user.user_idx)
                   or (study.assignee_user_idx is not None and int(study.assignee_user_idx) == user.user_idx)
                   or (report is not None and report.create_idx == user.user_idx)
                   or session.execute(select(func.count()).select_from(VcReportApproval).where(
                       VcReportApproval.study_idx == study.study_idx,
                       VcReportApproval.user_idx == user.user_idx)).scalar())
        if not allowed:
            raise VcError(404, "VC_APPROVAL_NOT_FOUND", "approval not found")
        return user

    def get_approval_for_reader(self, session: Session, manage_session: Session, user_idx, approval_id):
        self.require_operational()
        approval = self._load_approval(session, approval_id)
        self.require_reader(session, manage_session, user_idx, approval.study_idx)
        return approval

    def list_for_study(self, session: Session, manage_session: Session, user_idx, study_idx: int) -> list[dict]:
        self.require_operational()
        self.require_reader(session, manage_session, user_idx, study_idx)
        rows = session.execute(select(VcReportApproval).where(VcReportApproval.study_idx == int(study_idx))
                               .order_by(VcReportApproval.revision.desc())).scalars()
        return [self.approval_view(r) for r in rows]

    @staticmethod
    def approval_view(a: VcReportApproval) -> dict:
        def iso(v):
            return v.isoformat() + "Z" if v else None
        return {
            "approvalId": a.approval_id, "studyIdx": a.study_idx, "reportIdx": a.report_idx, "revision": a.revision,
            "targetStatus": a.target_status, "state": a.state, "stateReason": a.state_reason,
            "approved": a.state == APPROVED, "retryable": a.state in RECONCILE_STATES,
            "userIdx": a.user_idx, "vcTenantId": a.vc_tenant_id, "vcDoctorId": a.vc_doctor_id,
            "contentSha256": a.content_sha256, "snapshotSha256": a.snapshot_sha256,
            "supersedesApprovalId": a.supersedes_approval_id, "attemptCount": a.attempt_count,
            "lastErrorCode": a.last_error_code,
            "visionCertiSignRequestId": a.vc_sign_request_id, "visionCertiDocumentVersionId": a.vc_document_version_id,
            "signRequestExpiresAt": iso(a.vc_request_expires_at),
            "signature": None if a.vc_signature_id is None else {
                "signatureId": a.vc_signature_id, "algorithm": a.vc_signature_algorithm,
                "signatureMode": a.vc_signature_mode, "signatureValueBase64": a.vc_signature_value,
                "signedAt": iso(a.vc_signed_at), "certificateEnrollmentId": a.vc_certificate_enrollment_id,
                "certificateFingerprintSha256": a.vc_certificate_fingerprint,
                "certificateSerial": a.vc_certificate_serial, "keyName": a.vc_key_name,
                "keyVersion": a.vc_key_version, "publicKeySpki": a.vc_public_key_spki,
            },
            "createdAt": iso(a.created_at), "updatedAt": iso(a.updated_at), "committedAt": iso(a.committed_at),
        }


# ---------------------------------------------------------------------- legacy route guard

def guard_legacy_report_write(config: VcConfig, report_data: dict) -> None:
    """Called by legacy add/update report routes. When the integration is enabled (or misconfigured), A/RA can only
    be produced by a certificate-signed approval, and client-supplied approver fields are discarded."""
    if not config.guard_active:
        return
    if str(report_data.get("report_status") or "").upper() in ("A", "RA"):
        raise VcError(409, "VC_SIGNATURE_REQUIRED",
                      "판독 승인은 인증서 서명(Vision-Certi)으로만 할 수 있습니다. '인증서로 승인'을 사용하세요.")
    report_data.pop("report_approve_idx", None)
    report_data.pop("report_approve_datetime", None)


def guard_legacy_report_delete(config: VcConfig, session: Session, report_idx) -> None:
    """Reports with any approval evidence (signed or pending) cannot be deleted while the integration is on."""
    if not config.guard_active:
        return
    count = session.execute(select(func.count()).select_from(VcReportApproval)
                            .where(VcReportApproval.report_idx == int(report_idx))).scalar()
    if count:
        raise VcError(409, "VC_SIGNED_REPORT_IMMUTABLE", "인증서 승인 이력이 있는 판독문은 삭제할 수 없습니다.")
