"""Read/write access to the legacy PACS tables the integration depends on, through plain SQL (no legacy ORM import,
so no settings/.env load). Users come from the manage schema view ``pacs_users_view`` (same source as the legacy
token middleware); studies and reports from the main PACS schema.
"""
from __future__ import annotations

import datetime
import json
import re
from dataclasses import dataclass
from typing import Optional

from sqlalchemy import text
from sqlalchemy.orm import Session

_SCHEMA = re.compile(r"^[A-Za-z0-9_]{1,64}$")


@dataclass(frozen=True)
class LocalUser:
    user_idx: int
    user_id: str
    user_name: str
    active: bool
    pacs_user: bool
    is_doctor: bool
    group_level: int
    doctor_license_number: Optional[str]
    report_edit_all: bool


@dataclass(frozen=True)
class StudyInfo:
    study_idx: int
    assignee_user_idx: Optional[int]
    study_status: Optional[str]
    accession_no: Optional[str]
    patient_id: Optional[str]
    patient_name: Optional[str]
    modality: Optional[str]
    body_part: Optional[str]
    description: Optional[str]
    study_date: Optional[str]
    hospital_idx: Optional[int]
    hospital_name: Optional[str]


@dataclass(frozen=True)
class ReportInfo:
    report_idx: int
    study_idx: int
    status: Optional[str]
    reading: Optional[str]
    conclusion: Optional[str]
    study_comment: Optional[str]
    refer_comment: Optional[str]
    cvr: Optional[str]
    create_idx: Optional[int]
    open_edit: Optional[str]
    approve_idx: Optional[int]


class SqlLegacyPort:
    def __init__(self, manage_schema: str | None):
        if manage_schema is not None and not _SCHEMA.match(manage_schema):
            raise ValueError("invalid manage schema name")
        self._users = f"{manage_schema}.pacs_users_view" if manage_schema else "pacs_users_view"
        self._user_table = f"{manage_schema}.pacs_users" if manage_schema else "pacs_users"

    # ------------------------------------------------------------------ users (manage schema)

    def get_user(self, manage_session: Session, user_idx: int) -> Optional[LocalUser]:
        row = manage_session.execute(text(
            f"SELECT user_idx, user_id, user_name, user_status, user_type, group_level, doctor_idx, "
            f"doctor_license_number FROM {self._users} WHERE user_idx = :u"), {"u": int(user_idx)}).mappings().first()
        if row is None:
            return None
        try:
            with manage_session.begin_nested():
                edit_all = manage_session.execute(text(
                    f"SELECT user_report_edit_all FROM {self._user_table} WHERE user_idx = :u"),
                    {"u": int(user_idx)}).scalar()
        except Exception:  # column/table absent (migration not applied): legacy default, like crud/Token.py
            edit_all = "N"
        user_type = str(row["user_type"] or "")
        return LocalUser(
            user_idx=int(row["user_idx"]),
            user_id=str(row["user_id"]),
            user_name=row["user_name"] or "",
            active=row["user_status"] == "A",
            pacs_user="P" in [t.strip() for t in user_type.split(",")],
            is_doctor=row["doctor_idx"] is not None,
            group_level=int(row["group_level"] or 0),
            doctor_license_number=row["doctor_license_number"],
            report_edit_all=edit_all == "Y",
        )

    # ------------------------------------------------------------------ studies and reports (main schema)

    def enrollment_center(self, manage_session: Session, user_idx: int) -> str:
        raw = manage_session.execute(text(
            f"SELECT center_list FROM {self._users} WHERE user_idx = :u"), {"u": int(user_idx)}).scalar()
        try:
            centers = json.loads(raw) if isinstance(raw, str) else raw
            names = sorted({item["center_name"].strip() for item in centers
                            if isinstance(item, dict) and isinstance(item.get("center_name"), str)
                            and item["center_name"].strip()})
        except (TypeError, ValueError):
            return ""
        return ", ".join(names)

    def get_study(self, session: Session, study_idx: int) -> Optional[StudyInfo]:
        row = session.execute(text(
            "SELECT study_idx, user_idx, study_status, study_accession_no, patient_id, patient_name, study_modality, "
            "study_body_part, study_description, study_date, hospital_idx, hospital_name "
            "FROM pacs_study WHERE study_idx = :s"), {"s": int(study_idx)}).mappings().first()
        if row is None:
            return None
        study_date = row["study_date"]
        if isinstance(study_date, (datetime.date, datetime.datetime)):
            study_date = study_date.isoformat()
        return StudyInfo(
            study_idx=int(row["study_idx"]), assignee_user_idx=row["user_idx"], study_status=row["study_status"],
            accession_no=row["study_accession_no"], patient_id=row["patient_id"], patient_name=row["patient_name"],
            modality=row["study_modality"], body_part=row["study_body_part"], description=row["study_description"],
            study_date=str(study_date) if study_date is not None else None, hospital_idx=row["hospital_idx"],
            hospital_name=row["hospital_name"])

    _REPORT_COLUMNS = ("SELECT report_idx, study_idx, report_status, report_reading, report_conclusion, "
                       "report_study_comment, report_refer_comment, report_cvr_send, report_create_idx, "
                       "report_open_edit, report_approve_idx FROM pacs_study_report ")

    @staticmethod
    def _report(row) -> Optional[ReportInfo]:
        if row is None:
            return None
        return ReportInfo(
            report_idx=int(row["report_idx"]), study_idx=int(row["study_idx"]), status=row["report_status"],
            reading=row["report_reading"], conclusion=row["report_conclusion"],
            study_comment=row["report_study_comment"], refer_comment=row["report_refer_comment"],
            cvr=row["report_cvr_send"], create_idx=row["report_create_idx"], open_edit=row["report_open_edit"],
            approve_idx=row["report_approve_idx"])

    def get_report_for_study(self, session: Session, study_idx: int, *, for_update: bool = False):
        lock = " FOR UPDATE" if for_update and session.bind.dialect.name == "mysql" else ""
        row = session.execute(text(self._REPORT_COLUMNS + "WHERE study_idx = :s" + lock),
                              {"s": int(study_idx)}).mappings().first()
        return self._report(row)

    def get_report(self, session: Session, report_idx: int) -> Optional[ReportInfo]:
        row = session.execute(text(self._REPORT_COLUMNS + "WHERE report_idx = :r"),
                              {"r": int(report_idx)}).mappings().first()
        return self._report(row)

    def apply_approval(self, session: Session, report_idx: int, status: str, approver_user_idx: int,
                       approved_at: datetime.datetime) -> None:
        """Writes A/RA inside the caller's transaction (the DB trigger updates pacs_study.study_status)."""
        if status not in ("A", "RA"):
            raise ValueError("approval status must be A or RA")
        result = session.execute(text(
            "UPDATE pacs_study_report SET report_status = :st, report_approve_idx = :u, "
            "report_approve_datetime = :at, report_update_datetime = :at WHERE report_idx = :r"),
            {"st": status, "u": int(approver_user_idx), "at": approved_at, "r": int(report_idx)})
        if result.rowcount != 1:
            raise RuntimeError("report row not updated")
