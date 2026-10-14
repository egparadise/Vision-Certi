"""Server-to-server client for the Vision-Certi integration API.

- Only the configured base URL is ever contacted (paths are fixed constants, ids are validated UUIDs).
- TLS verification is always on (optional private CA bundle); plain http only to loopback when explicitly allowed.
- Bounded connect/read timeouts, a response size cap, and redirects are refused.
- No secret, handoff code or token is logged; errors carry only status and Vision-Certi error codes.
"""
from __future__ import annotations

import json
import socket
import ssl
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Callable, Protocol

from .config import VcConfig
from .hmac_auth import signed_headers

MAX_RESPONSE_BYTES = 1024 * 1024


class VcUnavailable(Exception):
    """Network failure, timeout, redirect, 5xx or malformed response. Always retryable; never means success."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class VcRejected(Exception):
    """Vision-Certi answered with a definitive 4xx."""

    def __init__(self, status: int, code: str):
        super().__init__(f"{status} {code}")
        self.status = status
        self.code = code


@dataclass
class RawResponse:
    status: int
    body: bytes


class Transport(Protocol):
    def send(self, method: str, url: str, headers: dict, body: bytes | None, timeout: float) -> RawResponse: ...


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 - urllib hook
        raise VcUnavailable("REDIRECT_REFUSED")


class UrllibTransport:
    """stdlib transport: TLS verified, redirects refused, size-capped reads."""

    def __init__(self, ca_bundle: str | None, connect_timeout: float):
        context = ssl.create_default_context(cafile=ca_bundle) if ca_bundle else ssl.create_default_context()
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        self._opener = urllib.request.build_opener(_NoRedirect(), urllib.request.HTTPSHandler(context=context))
        self._connect_timeout = connect_timeout

    def send(self, method, url, headers, body, timeout):
        request = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            # urllib applies one timeout to connect and each blocking read; use the larger as the hard bound.
            with self._opener.open(request, timeout=max(self._connect_timeout, timeout)) as response:
                data = response.read(MAX_RESPONSE_BYTES + 1)
                status = response.status
        except urllib.error.HTTPError as e:
            try:
                data = e.read(MAX_RESPONSE_BYTES + 1)
            except Exception:  # pragma: no cover - defensive
                data = b""
            status = e.code
        except VcUnavailable:
            raise
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, ssl.SSLError, OSError) as e:
            raise VcUnavailable(type(e).__name__)
        if len(data) > MAX_RESPONSE_BYTES:
            raise VcUnavailable("RESPONSE_TOO_LARGE")
        return RawResponse(status, data)


class VisionCertiClient:
    REDEEM = "/v1/integrations/pacs/handoffs/redeem"
    SIGN_REQUESTS = "/v1/integrations/pacs/report-sign-requests"

    def __init__(self, config: VcConfig, transport: Transport | None = None,
                 clock: Callable[[], float] = time.time):
        self._config = config
        self._transport = transport or UrllibTransport(config.ca_bundle, config.connect_timeout)
        self._clock = clock

    def redeem_handoff(self, code: str) -> dict:
        return self._call("POST", self.REDEEM, {"handoffCode": code})

    def create_report_sign_request(self, payload: dict, idempotency_key: str) -> dict:
        return self._call("POST", self.SIGN_REQUESTS, payload, {"Idempotency-Key": idempotency_key})

    def get_report_sign_request(self, sign_request_id: str) -> dict:
        canonical_id = str(uuid.UUID(sign_request_id))  # never interpolate unvalidated text into the path
        return self._call("GET", f"{self.SIGN_REQUESTS}/{canonical_id}", None)

    def _call(self, method: str, path: str, payload: dict | None, extra_headers: dict | None = None) -> dict:
        if not self._config.operational:
            raise VcUnavailable("NOT_CONFIGURED")
        body = b"" if payload is None else json.dumps(payload, separators=(",", ":"), ensure_ascii=False) \
            .encode("utf-8")
        headers = signed_headers(secret=self._config.secret, audience=self._config.audience,
                                 client_id=self._config.client_id, method=method, raw_path=path, raw_query=None,
                                 body=body, timestamp=int(self._clock()))
        headers["Accept"] = "application/json"
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if extra_headers:
            headers.update(extra_headers)
        response = self._transport.send(method, self._config.base_url + path, headers,
                                        body if payload is not None else None, self._config.read_timeout)
        try:
            data = json.loads(response.body.decode("utf-8")) if response.body else {}
        except (UnicodeDecodeError, ValueError):
            data = None
        if 200 <= response.status < 300:
            if not isinstance(data, dict):
                raise VcUnavailable("MALFORMED_RESPONSE")
            return data
        code = data.get("code") if isinstance(data, dict) and isinstance(data.get("code"), str) else "UNKNOWN"
        if response.status >= 500 or code in ("INTEGRATION_NONCE_REPLAYED", "INTEGRATION_BUSY"):
            raise VcUnavailable(f"HTTP_{response.status}_{code}")
        if 400 <= response.status < 500:
            raise VcRejected(response.status, code)
        raise VcUnavailable(f"HTTP_{response.status}")
