"""VC-HMAC-SHA256-V1 request signing (mirror of Vision-Certi IntegrationAuthFilter)."""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets

SCHEME = "VC-HMAC-SHA256-V1"


def canonical(audience: str, client_id: str, method: str, raw_path: str, raw_query: str | None, timestamp: str,
              nonce: str, content_sha256: str) -> str:
    return "\n".join([SCHEME, audience, client_id, method, raw_path, raw_query or "", timestamp, nonce,
                      content_sha256])


def sign(secret: bytes, canonical_string: str) -> str:
    digest = hmac.new(secret, canonical_string.encode("utf-8"), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def new_nonce() -> str:
    return secrets.token_urlsafe(24)  # 32 chars of [A-Za-z0-9_-]


def signed_headers(*, secret: bytes, audience: str, client_id: str, method: str, raw_path: str,
                   raw_query: str | None, body: bytes, timestamp: int, nonce: str | None = None) -> dict:
    nonce = nonce or new_nonce()
    content_sha = hashlib.sha256(body).hexdigest()
    ts = str(int(timestamp))
    signature = sign(secret, canonical(audience, client_id, method, raw_path, raw_query, ts, nonce, content_sha))
    return {
        "X-VC-Client-Id": client_id,
        "X-VC-Timestamp": ts,
        "X-VC-Nonce": nonce,
        "X-VC-Content-SHA256": content_sha,
        "X-VC-Signature": signature,
    }
