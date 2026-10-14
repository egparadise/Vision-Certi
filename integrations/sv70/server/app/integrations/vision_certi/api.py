"""HTTP layer for /api/vc. Built from injected dependencies so tests run without legacy settings, MySQL or MinIO.

The running server wires real dependencies in app/router/VisionCerti.py. Tokens and handoff codes are only ever read
from the Authorization header or the JSON body, never from the URL, and are never logged.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response
from sqlalchemy.orm import Session

from .service import VcError, VcIntegrationService

_NO_STORE = {"Cache-Control": "no-store", "Pragma": "no-cache"}


@dataclass
class VcApiDeps:
    service: Callable[[], VcIntegrationService]
    main_session: Callable[..., Any]       # FastAPI dependency yielding the main PACS DB session
    manage_session: Callable[..., Any]     # FastAPI dependency yielding the manage DB session
    legacy_user_idx: Callable[[Request], Any]  # user_idx authenticated by the legacy token middleware (or None)


def _error(e: VcError) -> JSONResponse:
    body = {"status": e.status, "code": e.code, "message": e.message, "retryable": e.retryable}
    body.update(e.data)
    return JSONResponse(status_code=e.status, content=body, headers=_NO_STORE)


def _ok(body: dict, status: int = 200) -> JSONResponse:
    return JSONResponse(status_code=status, content=body, headers=_NO_STORE)


async def _json_body(request: Request) -> dict:
    try:
        data = await request.json()
    except Exception:
        raise VcError(422, "VC_VALIDATION_FAILED", "JSON body required")
    if not isinstance(data, dict):
        raise VcError(422, "VC_VALIDATION_FAILED", "JSON object required")
    return data


def _client_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


def build_router(deps: VcApiDeps) -> APIRouter:
    router = APIRouter(prefix="/vc", tags=["vision-certi"])

    def principal(request: Request, session: Session, manage: Session):
        return deps.service().resolve_session(session, manage, request.headers.get("Authorization"))

    @router.get("/config")
    def config():
        return _ok(deps.service().config.public_view())

    @router.post("/auth/certificate-login")
    async def certificate_login(request: Request, session: Session = Depends(deps.main_session),
                                manage: Session = Depends(deps.manage_session)):
        try:
            body = await _json_body(request)
            service = deps.service()
            token, row, user = service.certificate_login(session, manage, body.get("handoffCode"),
                                                         _client_ip(request))
            return _ok({
                "token": token,
                "expiresAt": row.expires_at.isoformat() + "Z",
                "user_data": {"user_idx": user.user_idx, "user_id": user.user_id, "user_name": user.user_name,
                              "group_level": user.group_level, "doctor_license_number": user.doctor_license_number},
                "vc": {"tenantId": row.vc_tenant_id, "doctorId": row.vc_doctor_id,
                       "certificateEnrollmentId": row.vc_enrollment_id,
                       "certificateFingerprintSha256": row.vc_certificate_fingerprint,
                       "identityMode": row.vc_identity_mode,
                       "sessionExpiresAt": row.vc_session_expires_at.isoformat() + "Z"},
            })
        except VcError as e:
            return _error(e)

    @router.get("/auth/me")
    @router.get("/auth/session")
    def current_session(request: Request, session: Session = Depends(deps.main_session),
                        manage: Session = Depends(deps.manage_session)):
        try:
            return _ok(VcIntegrationService.session_view(principal(request, session, manage)))
        except VcError as e:
            return _error(e)

    @router.post("/auth/logout")
    def logout(request: Request, session: Session = Depends(deps.main_session),
               manage: Session = Depends(deps.manage_session)):
        try:
            deps.service().logout(session, principal(request, session, manage))
            return _ok({"loggedOut": True})
        except VcError as e:
            return _error(e)

    # ---------------------------------------------------------------- admin links

    @router.get("/admin/doctor-links")
    def list_links(request: Request, session: Session = Depends(deps.main_session),
                   manage: Session = Depends(deps.manage_session)):
        try:
            return _ok({"items": deps.service().list_links(session, manage, deps.legacy_user_idx(request))})
        except VcError as e:
            return _error(e)

    @router.post("/admin/doctor-links")
    async def create_link(request: Request, session: Session = Depends(deps.main_session),
                          manage: Session = Depends(deps.manage_session)):
        try:
            body = await _json_body(request)
            return _ok(deps.service().create_link(session, manage, deps.legacy_user_idx(request), body), 201)
        except VcError as e:
            return _error(e)

    @router.post("/admin/doctor-links/{link_idx}/revoke")
    async def revoke_link(link_idx: int, request: Request, session: Session = Depends(deps.main_session),
                          manage: Session = Depends(deps.manage_session)):
        try:
            body = await _json_body(request)
            return _ok(deps.service().revoke_link(session, manage, deps.legacy_user_idx(request), link_idx,
                                                  body.get("reason")))
        except VcError as e:
            return _error(e)

    # ---------------------------------------------------------------- report approval

    @router.get("/study/{study_idx}/report/current")
    def current_report(study_idx: int, request: Request, session: Session = Depends(deps.main_session),
                       manage: Session = Depends(deps.manage_session)):
        try:
            service = deps.service()
            p = principal(request, session, manage)
            return _ok(service.current_report(session, p, study_idx))
        except VcError as e:
            return _error(e)

    @router.post("/study/{study_idx}/report/prepare")
    async def prepare(study_idx: int, request: Request, session: Session = Depends(deps.main_session),
                      manage: Session = Depends(deps.manage_session)):
        try:
            body = await _json_body(request)
            service = deps.service()
            p = principal(request, session, manage)
            approval, status = service.prepare(session, manage, p, study_idx, body.get("expected_content_sha256"))
            return _ok({"approval": service.approval_view(approval), "snapshot": approval.snapshot_json,
                        "snapshotSha256": approval.snapshot_sha256,
                        "visionCertiSignRequestId": approval.vc_sign_request_id}, status)
        except VcError as e:
            return _error(e)

    @router.post("/report-approvals/{approval_id}/finalize")
    def finalize(approval_id: str, request: Request, session: Session = Depends(deps.main_session),
                 manage: Session = Depends(deps.manage_session)):
        try:
            service = deps.service()
            p = principal(request, session, manage)
            approval, status = service.finalize(session, manage, approval_id, principal=p)
            return _ok({"approval": service.approval_view(approval)}, status)
        except VcError as e:
            return _error(e)

    @router.post("/admin/report-approvals/reconcile")
    def reconcile(request: Request, session: Session = Depends(deps.main_session),
                  manage: Session = Depends(deps.manage_session)):
        try:
            return _ok({"results": deps.service().reconcile(session, manage, deps.legacy_user_idx(request))})
        except VcError as e:
            return _error(e)

    @router.get("/report-approvals/{approval_id}")
    def get_approval(approval_id: str, request: Request, session: Session = Depends(deps.main_session),
                     manage: Session = Depends(deps.manage_session)):
        try:
            service = deps.service()
            approval = service.get_approval_for_reader(session, manage, deps.legacy_user_idx(request), approval_id)
            return _ok(service.approval_view(approval))
        except VcError as e:
            return _error(e)

    @router.get("/report-approvals/{approval_id}/snapshot")
    def get_snapshot(approval_id: str, request: Request, session: Session = Depends(deps.main_session),
                     manage: Session = Depends(deps.manage_session)):
        try:
            approval = deps.service().get_approval_for_reader(session, manage, deps.legacy_user_idx(request),
                                                              approval_id)
            return Response(content=approval.snapshot_json.encode("utf-8"),
                            media_type="application/vnd.saintview.pacs-report+json; version=1",
                            headers={**_NO_STORE, "X-Snapshot-Sha256": approval.snapshot_sha256,
                                     "X-Content-Type-Options": "nosniff"})
        except VcError as e:
            return _error(e)

    @router.get("/study/{study_idx}/report/approvals")
    def list_approvals(study_idx: int, request: Request, session: Session = Depends(deps.main_session),
                       manage: Session = Depends(deps.manage_session)):
        try:
            return _ok({"items": deps.service().list_for_study(session, manage, deps.legacy_user_idx(request),
                                                               study_idx)})
        except VcError as e:
            return _error(e)

    return router
