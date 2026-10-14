"""Live-server wiring. Legacy modules (settings, databases) are imported lazily inside functions so that importing
the integration package never loads the legacy .env configuration.
"""
from __future__ import annotations

import threading

from .api import VcApiDeps
from .client import VisionCertiClient
from .config import load_config
from .legacy_port import SqlLegacyPort
from .service import TOKEN_PREFIX, VcError, VcIntegrationService

_lock = threading.Lock()
_service: VcIntegrationService | None = None


def service() -> VcIntegrationService:
    global _service
    if _service is None:
        with _lock:
            if _service is None:
                from app.core import settings
                from app.core.constants import SUPER_ADMIN_USER_IDX_LIST
                config = load_config(super_admins=SUPER_ADMIN_USER_IDX_LIST)
                _service = VcIntegrationService(config, VisionCertiClient(config),
                                                SqlLegacyPort(settings.db_manager_schema),
                                                super_admins=SUPER_ADMIN_USER_IDX_LIST)
    return _service


def main_session():
    from app.database import db
    yield from db.session()


def manage_session():
    from app.database import manageDB
    yield from manageDB.session()


def legacy_user_idx(request):
    user = getattr(request.state, "user", None)
    return user.get("user_idx") if isinstance(user, dict) else None


def api_deps() -> VcApiDeps:
    return VcApiDeps(service=service, main_session=main_session, manage_session=manage_session,
                     legacy_user_idx=legacy_user_idx)


def is_vc_token(token: str | None) -> bool:
    return bool(token) and token.startswith(TOKEN_PREFIX)


def resolve_vc_token_user_idx(token: str) -> int:
    """Used by the legacy token middleware (crud/Token.get_user) for ``vc1.`` bearers: a fresh, uncached check of the
    certificate session, its link and the local doctor account. Any failure looks like an expired legacy token."""
    from app.database import db, manageDB
    from app.errors.exceptions import TokenExpiredEx
    main_gen, manage_gen = db.session(), manageDB.session()
    main, manage = next(main_gen), next(manage_gen)
    try:
        return service().resolve_session(main, manage, "Bearer " + token).user_idx
    except VcError:
        raise TokenExpiredEx()
    finally:
        main_gen.close()
        manage_gen.close()


def legacy_report_write_guard(report_data: dict) -> None:
    from app.integrations.vision_certi.service import guard_legacy_report_write
    _raise_http(lambda: guard_legacy_report_write(service().config, report_data))


def legacy_report_delete_guard(session, report_idx) -> None:
    from app.integrations.vision_certi.service import guard_legacy_report_delete
    _raise_http(lambda: guard_legacy_report_delete(service().config, session, report_idx))


def _raise_http(action) -> None:
    from fastapi import HTTPException
    try:
        action()
    except VcError as e:
        raise HTTPException(status_code=e.status, detail={"code": e.code, "message": e.message})
