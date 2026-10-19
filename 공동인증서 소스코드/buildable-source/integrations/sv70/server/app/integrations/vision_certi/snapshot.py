"""Immutable report snapshot: canonical UTF-8 JSON bytes whose SHA-256 is what the physician signs."""
from __future__ import annotations

import hashlib
import json

SCHEMA = "saintview-pacs-report-snapshot/v1"
CONTENT_TYPE = "application/vnd.saintview.pacs-report+json;version=1"


def canonical_json(value) -> bytes:
    """Deterministic encoding: sorted keys, no whitespace, UTF-8, no NaN. Same input → same bytes."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) \
        .encode("utf-8")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def report_content(report) -> dict:
    """The legacy report fields that carry clinical meaning (what a revision is made of)."""
    return {
        "reportIdx": report.report_idx,
        "studyIdx": report.study_idx,
        "reading": report.reading or "",
        "conclusion": report.conclusion or "",
        "studyComment": report.study_comment or "",
        "referComment": report.refer_comment or "",
        "cvr": report.cvr or "N",
        "createdByUserIdx": report.create_idx,
    }


def content_sha256(report) -> str:
    return sha256_hex(canonical_json(report_content(report)))


def external_report_id(client_id: str, report_idx: int) -> str:
    return f"{client_id}:report:{int(report_idx)}"


def external_study_id(client_id: str, study_idx: int) -> str:
    return f"{client_id}:study:{int(study_idx)}"


def build_snapshot(*, client_id: str, tenant_id: str, doctor_id: str, revision: int, target_status: str,
                   report, study, user, content_sha: str, supersedes_approval_id: str | None,
                   prior_signed_snapshot_sha256: str | None) -> bytes:
    """Builds the exact bytes from server-side DB values only (never from browser input)."""
    content = report_content(report)
    document = {
        "schema": SCHEMA,
        "tenantId": tenant_id,
        "doctorId": doctor_id,
        "pacs": {
            "clientId": client_id,
            "userIdx": user.user_idx,
            "userId": str(user.user_id),
            "userName": user.user_name or "",
            "doctorLicenseNumber": user.doctor_license_number or "",
        },
        "study": {
            "externalStudyId": external_study_id(client_id, study.study_idx),
            "studyIdx": study.study_idx,
            "accessionNo": study.accession_no or "",
            "patientId": study.patient_id or "",
            "patientName": study.patient_name or "",
            "modality": study.modality or "",
            "bodyPart": study.body_part or "",
            "description": study.description or "",
            "studyDate": study.study_date or "",
            "hospitalIdx": study.hospital_idx,
            "hospitalName": study.hospital_name or "",
        },
        "report": {
            "externalReportId": external_report_id(client_id, report.report_idx),
            "revision": int(revision),
            "approvalStatus": target_status,
            "contentSha256": content_sha,
            "supersedesApprovalId": supersedes_approval_id,
            "priorSignedSnapshotSha256": prior_signed_snapshot_sha256,
            **{k: v for k, v in content.items() if k not in ("studyIdx",)},
        },
    }
    return canonical_json(document)
