"""Integration configuration from process environment variables only (never from the legacy .env files).

States:
- DISABLED (default): legacy behaviour is unchanged and every /api/vc route answers 404-like "disabled".
- ENABLED: fully configured.
- MISCONFIGURED: enabled but invalid. Fail closed: /api/vc routes answer 503 and legacy A/RA writes stay blocked.
"""
from __future__ import annotations

import ipaddress
import os
import re
from dataclasses import dataclass, field
from typing import Iterable, Mapping
from urllib.parse import urlsplit

DISABLED = "DISABLED"
ENABLED = "ENABLED"
MISCONFIGURED = "MISCONFIGURED"

DEFAULT_AUDIENCE = "vision-certi:pacs-integration:v1"
_CLIENT_ID = re.compile(r"^[A-Za-z0-9._-]{3,64}$")
_TENANT_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
_LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
_TRUE = {"1", "true", "yes", "on"}
MIN_SECRET_BYTES = 32


@dataclass(frozen=True)
class VcConfig:
    state: str
    problems: tuple = ()
    base_url: str = ""
    public_base_url: str = ""
    client_id: str = ""
    audience: str = DEFAULT_AUDIENCE
    tenant_id: str = ""
    ca_bundle: str | None = None
    connect_timeout: float = 3.0
    read_timeout: float = 10.0
    session_max_minutes: int = 60
    link_admin_user_idx: frozenset = frozenset()
    secret: bytes = field(default=b"", repr=False)

    @property
    def guard_active(self) -> bool:
        """Legacy A/RA protection applies whenever the integration is not explicitly disabled."""
        return self.state != DISABLED

    @property
    def operational(self) -> bool:
        return self.state == ENABLED

    def public_view(self) -> dict:
        return {
            "enabled": self.state != DISABLED,
            "state": self.state,
            "visionCertiBaseUrl": self.public_base_url if self.operational else None,
            "audience": self.client_id if self.operational else None,
            "tenantId": self.tenant_id if self.operational else None,
        }


def _is_true(value: str | None) -> bool:
    return (value or "").strip().lower() in _TRUE


def _check_url(name: str, value: str, allow_local_http: bool, problems: list) -> str:
    try:
        parts = urlsplit(value)
    except ValueError:
        problems.append(f"{name} is not a URL")
        return ""
    host = (parts.hostname or "").lower()
    if parts.username or parts.password or parts.query or parts.fragment or parts.path not in ("", "/"):
        problems.append(f"{name} must be scheme://host[:port] without credentials, path, query or fragment")
    if not host:
        problems.append(f"{name} has no host")
    if parts.scheme == "https":
        pass
    elif parts.scheme == "http":
        is_loopback = host in _LOCAL_HOSTS
        if not is_loopback:
            try:
                is_loopback = ipaddress.ip_address(host).is_loopback
            except ValueError:
                is_loopback = False
        if not (allow_local_http and is_loopback):
            problems.append(f"{name} must use https (http only to localhost with VISION_CERTI_ALLOW_LOCALHOST_HTTP)")
    else:
        problems.append(f"{name} must use https")
    return f"{parts.scheme}://{parts.netloc}"


def _float(env: Mapping[str, str], key: str, default: float, low: float, high: float, problems: list) -> float:
    raw = env.get(key)
    if raw is None or raw == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        problems.append(f"{key} is not a number")
        return default
    if not low <= value <= high:
        problems.append(f"{key} must be within [{low}, {high}]")
    return value


def load_config(env: Mapping[str, str] | None = None, super_admins: Iterable[int] = ()) -> VcConfig:
    env = os.environ if env is None else env
    if not _is_true(env.get("VISION_CERTI_INTEGRATION_ENABLED")):
        return VcConfig(state=DISABLED)
    problems: list[str] = []
    allow_local_http = _is_true(env.get("VISION_CERTI_ALLOW_LOCALHOST_HTTP"))
    base_url = _check_url("VISION_CERTI_BASE_URL", (env.get("VISION_CERTI_BASE_URL") or "").strip(),
                          allow_local_http, problems)
    public_raw = (env.get("VISION_CERTI_PUBLIC_BASE_URL") or "").strip()
    public_base_url = _check_url("VISION_CERTI_PUBLIC_BASE_URL", public_raw, allow_local_http, problems) \
        if public_raw else base_url
    client_id = (env.get("VISION_CERTI_CLIENT_ID") or "").strip()
    if not _CLIENT_ID.match(client_id):
        problems.append("VISION_CERTI_CLIENT_ID is missing or invalid")
    tenant_id = (env.get("VISION_CERTI_TENANT_ID") or "").strip()
    if not _TENANT_ID.match(tenant_id):
        problems.append("VISION_CERTI_TENANT_ID is missing or invalid")
    audience = (env.get("VISION_CERTI_AUDIENCE") or DEFAULT_AUDIENCE).strip()
    secret = b""
    secret_file = (env.get("VISION_CERTI_CLIENT_SECRET_FILE") or "").strip()
    if not secret_file:
        problems.append("VISION_CERTI_CLIENT_SECRET_FILE is required (inline secrets are not accepted)")
    else:
        try:
            with open(secret_file, "rb") as handle:
                secret = handle.read(4096).strip()
        except OSError:
            problems.append("VISION_CERTI_CLIENT_SECRET_FILE is not readable")
        if secret_file and len(secret) < MIN_SECRET_BYTES and not any("not readable" in p for p in problems):
            problems.append(f"client secret must be at least {MIN_SECRET_BYTES} bytes")
    ca_bundle = (env.get("VISION_CERTI_CA_BUNDLE") or "").strip() or None
    if ca_bundle and not os.path.isfile(ca_bundle):
        problems.append("VISION_CERTI_CA_BUNDLE is not a readable file")
    connect_timeout = _float(env, "VISION_CERTI_CONNECT_TIMEOUT", 3.0, 0.5, 10.0, problems)
    read_timeout = _float(env, "VISION_CERTI_READ_TIMEOUT", 10.0, 1.0, 30.0, problems)
    session_max = int(_float(env, "VISION_CERTI_SESSION_MAX_MINUTES", 60, 5, 240, problems))
    admins_raw = (env.get("VISION_CERTI_LINK_ADMIN_USER_IDX") or "").strip()
    admins: set[int] = set()
    if admins_raw:
        for item in admins_raw.split(","):
            item = item.strip()
            if item:
                try:
                    admins.add(int(item))
                except ValueError:
                    problems.append("VISION_CERTI_LINK_ADMIN_USER_IDX must be a comma list of user_idx")
    else:
        admins = {int(x) for x in super_admins}
    return VcConfig(
        state=MISCONFIGURED if problems else ENABLED,
        problems=tuple(problems),
        base_url=base_url,
        public_base_url=public_base_url,
        client_id=client_id,
        audience=audience,
        tenant_id=tenant_id,
        ca_bundle=ca_bundle,
        connect_timeout=connect_timeout,
        read_timeout=read_timeout,
        session_max_minutes=session_max,
        link_admin_user_idx=frozenset(admins),
        secret=secret if not problems else b"",
    )
