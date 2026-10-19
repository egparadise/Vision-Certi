"""Minimal ECDSA P-256 / SHA-256 signature *verification* (no signing, no secrets) so the PACS server can check
Vision-Certi signature evidence over the exact snapshot bytes without a new dependency. If the ``cryptography``
package is importable it is used instead; both paths reject malformed input.
"""
from __future__ import annotations

import base64
import hashlib

# NIST P-256 (secp256r1)
_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
_A = _P - 3
_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
_G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
      0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)

# DER prefix of SubjectPublicKeyInfo for id-ecPublicKey + prime256v1 with an uncompressed point.
_SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")


class InvalidSignature(Exception):
    pass


def _on_curve(point) -> bool:
    x, y = point
    return 0 <= x < _P and 0 <= y < _P and (y * y - (x * x * x + _A * x + _B)) % _P == 0


def _add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % _P == 0:
        return None
    if p1 == p2:
        lam = (3 * x1 * x1 + _A) * pow(2 * y1, -1, _P) % _P
    else:
        lam = (y2 - y1) * pow(x2 - x1, -1, _P) % _P
    x3 = (lam * lam - x1 - x2) % _P
    return x3, (lam * (x1 - x3) - y1) % _P


def _mul(k: int, point):
    result = None
    addend = point
    while k:
        if k & 1:
            result = _add(result, addend)
        addend = _add(addend, addend)
        k >>= 1
    return result


def _public_point(spki_der: bytes):
    if len(spki_der) != len(_SPKI_PREFIX) + 65 or not spki_der.startswith(_SPKI_PREFIX):
        raise InvalidSignature("public key is not an uncompressed P-256 SubjectPublicKeyInfo")
    raw = spki_der[len(_SPKI_PREFIX):]
    if raw[0] != 0x04:
        raise InvalidSignature("public key point is not uncompressed")
    point = (int.from_bytes(raw[1:33], "big"), int.from_bytes(raw[33:65], "big"))
    if not _on_curve(point):
        raise InvalidSignature("public key point is not on P-256")
    return point


def _der_int(data: bytes, offset: int):
    if offset + 2 > len(data) or data[offset] != 0x02:
        raise InvalidSignature("bad DER integer")
    length = data[offset + 1]
    if length == 0 or length > 33 or offset + 2 + length > len(data):
        raise InvalidSignature("bad DER integer length")
    body = data[offset + 2: offset + 2 + length]
    if body[0] & 0x80 or (length > 1 and body[0] == 0 and not body[1] & 0x80):
        raise InvalidSignature("non-canonical DER integer")
    return int.from_bytes(body, "big"), offset + 2 + length


def _parse_der_signature(sig: bytes):
    if len(sig) < 8 or sig[0] != 0x30 or sig[1] != len(sig) - 2:
        raise InvalidSignature("bad DER signature")
    r, offset = _der_int(sig, 2)
    s, offset = _der_int(sig, offset)
    if offset != len(sig):
        raise InvalidSignature("trailing bytes in signature")
    return r, s


def _verify_pure(spki_der: bytes, message: bytes, der_signature: bytes) -> None:
    q = _public_point(spki_der)
    r, s = _parse_der_signature(der_signature)
    if not (1 <= r < _N and 1 <= s < _N):
        raise InvalidSignature("r or s out of range")
    e = int.from_bytes(hashlib.sha256(message).digest(), "big")
    w = pow(s, -1, _N)
    point = _add(_mul(e * w % _N, _G), _mul(r * w % _N, q))
    if point is None or point[0] % _N != r:
        raise InvalidSignature("signature does not verify")


def verify(spki_base64: str, message: bytes, der_signature_base64: str) -> None:
    """Raises InvalidSignature unless the DER ECDSA P-256/SHA-256 signature verifies over ``message``."""
    try:
        spki = base64.b64decode(spki_base64, validate=True)
        signature = base64.b64decode(der_signature_base64, validate=True)
    except (ValueError, TypeError):
        raise InvalidSignature("not base64")
    _public_point(spki)  # always enforce the P-256 key policy, whichever backend verifies
    try:
        from cryptography.exceptions import InvalidSignature as _CryptoInvalid
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.serialization import load_der_public_key
    except ImportError:
        _verify_pure(spki, message, signature)
        return
    try:
        key = load_der_public_key(spki)
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except (_CryptoInvalid, ValueError, TypeError):
        raise InvalidSignature("signature does not verify")
