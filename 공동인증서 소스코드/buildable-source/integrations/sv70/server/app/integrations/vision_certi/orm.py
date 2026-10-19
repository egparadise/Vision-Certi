"""ORM for the integration tables (contract: work/sv70-api-db-contract.md §3). Root owns the reviewed MySQL
migration; these classes must match it. They live in the main PACS schema (same DB as pacs_study_report) so the
report approval and the legacy A/RA write commit in one transaction. No legacy table is altered.
"""
from __future__ import annotations

import datetime
from typing import Optional

from sqlalchemy import BigInteger, DateTime, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.dialects import mysql
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

_DT = DateTime().with_variant(mysql.DATETIME(fsp=6), "mysql")
_LONGTEXT = Text().with_variant(mysql.LONGTEXT(), "mysql")
_BIGINT = BigInteger().with_variant(Integer(), "sqlite")  # SQLite autoincrement needs INTEGER


class VcBase(DeclarativeBase):
    pass


class VcDoctorLink(VcBase):
    __tablename__ = "pacs_vc_doctor_link"
    __table_args__ = (
        Index("ix_pacs_vc_doctor_link_doctor", "vc_tenant_id", "vc_doctor_id"),
        Index("ix_pacs_vc_doctor_link_user", "user_idx"),
        {"comment": "Vision-Certi doctor <-> PACS user explicit admin mapping (append-only)"},
    )

    link_idx: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    vc_tenant_id: Mapped[str] = mapped_column(String(64), nullable=False)
    vc_doctor_id: Mapped[str] = mapped_column(String(36), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)  # ACTIVE | REVOKED (ENUM in MySQL)
    active_user_idx: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, unique=True)
    active_doctor_key: Mapped[Optional[str]] = mapped_column(String(101), nullable=True, unique=True)
    evidence_ref: Mapped[str] = mapped_column(String(255), nullable=False)
    note: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    created_by_user_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    revoked_by_user_idx: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    revoked_at: Mapped[Optional[datetime.datetime]] = mapped_column(_DT, nullable=True)
    revoke_reason: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)


class VcSession(VcBase):
    __tablename__ = "pacs_vc_session"
    __table_args__ = (
        Index("ix_pacs_vc_session_user", "user_idx"),
        Index("ix_pacs_vc_session_expires", "expires_at"),
        {"comment": "PACS bearer sessions created from a Vision-Certi certificate login (token stored as SHA-256)"},
    )

    session_idx: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    token_sha256: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    user_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    link_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    vc_tenant_id: Mapped[str] = mapped_column(String(64), nullable=False)
    vc_doctor_id: Mapped[str] = mapped_column(String(36), nullable=False)
    vc_enrollment_id: Mapped[str] = mapped_column(String(36), nullable=False)
    vc_certificate_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    vc_identity_mode: Mapped[str] = mapped_column(String(32), nullable=False)
    vc_authenticated_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    vc_session_expires_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    expires_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    revoked_at: Mapped[Optional[datetime.datetime]] = mapped_column(_DT, nullable=True)
    revoke_reason: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    client_ip: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)


class VcReportApproval(VcBase):
    __tablename__ = "pacs_vc_report_approval"
    __table_args__ = (
        UniqueConstraint("report_idx", "revision", name="uq_pacs_vc_report_approval_revision"),
        Index("ix_pacs_vc_report_approval_study", "study_idx"),
        Index("ix_pacs_vc_report_approval_state", "state"),
        {"comment": "Certificate-signed report approvals; one row per prepared revision, evidence write-once"},
    )

    approval_idx: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    approval_id: Mapped[str] = mapped_column(String(36), nullable=False, unique=True)
    study_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    report_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    target_status: Mapped[str] = mapped_column(String(2), nullable=False)  # A | RA (ENUM in MySQL)
    user_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    link_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    session_idx: Mapped[int] = mapped_column(Integer, nullable=False)
    vc_tenant_id: Mapped[str] = mapped_column(String(64), nullable=False)
    vc_doctor_id: Mapped[str] = mapped_column(String(36), nullable=False)
    content_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    snapshot_json: Mapped[str] = mapped_column(_LONGTEXT, nullable=False)
    snapshot_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    state: Mapped[str] = mapped_column(String(32), nullable=False)
    state_reason: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    attempt_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_error_code: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    vc_sign_request_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True)
    vc_document_version_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True)
    vc_request_expires_at: Mapped[Optional[datetime.datetime]] = mapped_column(_DT, nullable=True)
    vc_signature_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True)
    vc_signature_algorithm: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    vc_signature_mode: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    vc_signature_value: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    vc_signed_at: Mapped[Optional[datetime.datetime]] = mapped_column(_DT, nullable=True)
    vc_certificate_enrollment_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True)
    vc_certificate_fingerprint: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    vc_certificate_serial: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    vc_key_name: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    vc_key_version: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    vc_public_key_spki: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    vc_evidence_json: Mapped[Optional[str]] = mapped_column(_LONGTEXT, nullable=True)
    supersedes_approval_id: Mapped[Optional[str]] = mapped_column(String(36), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    updated_at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    committed_at: Mapped[Optional[datetime.datetime]] = mapped_column(_DT, nullable=True)


class VcAudit(VcBase):
    __tablename__ = "pacs_vc_audit"
    __table_args__ = ({"comment": "Integration audit trail (never tokens, codes, secrets or report text)"},)

    audit_idx: Mapped[int] = mapped_column(_BIGINT, primary_key=True, autoincrement=True)
    at: Mapped[datetime.datetime] = mapped_column(_DT, nullable=False)
    actor_user_idx: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    target_type: Mapped[str] = mapped_column(String(32), nullable=False)
    target_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    outcome: Mapped[str] = mapped_column(String(16), nullable=False)
    detail_json: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
