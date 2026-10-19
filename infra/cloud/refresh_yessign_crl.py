#!/usr/bin/python3
"""Refresh the issuer-signed yessign CRL while the operator PC is offline.

Run on the dedicated OpenBao VM with only accessor access to the public CA/CRL
secrets and version-adder access to the CRL secret. Never accepts an unverified CRL.
"""

import base64
import json
import urllib.request
from datetime import datetime, timezone

from cryptography import x509
from ldap3 import BASE, NONE, Connection, Server

PROJECT = "hahattalk"
CA_SECRET = "vision-certi-pc-trust-yessign"
CRL_SECRET = "vision-certi-pc-crl-yessign"
LDAP_BASE = "ou=dp6p47981,ou=AccreditedCA,o=yessign,c=kr"


def access_token():
    req = urllib.request.Request(
        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token",
        headers={"Metadata-Flavor": "Google"},
    )
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)["access_token"]


def secret_request(token, name, action, payload=None):
    url = f"https://secretmanager.googleapis.com/v1/projects/{PROJECT}/secrets/{name}{action}"
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST" if body is not None else "GET",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def read_secret(token, name):
    result = secret_request(token, name, "/versions/latest:access")
    return base64.b64decode(result["payload"]["data"])


def fetch_crl():
    server = Server("ds.yessign.or.kr", port=389, get_info=NONE, connect_timeout=10)
    with Connection(server, auto_bind=True, receive_timeout=15) as connection:
        if not connection.search(
            LDAP_BASE,
            "(objectClass=*)",
            search_scope=BASE,
            attributes=["certificateRevocationList;binary"],
        ):
            raise RuntimeError("yessign LDAP CRL entry unavailable")
        values = connection.entries[0].entry_raw_attributes.get("certificateRevocationList", [])
        if len(values) != 1:
            raise RuntimeError("yessign LDAP CRL value missing or ambiguous")
        return values[0]


def main():
    token = access_token()
    ca = x509.load_der_x509_certificate(read_secret(token, CA_SECRET))
    current_bytes = read_secret(token, CRL_SECRET)
    latest_bytes = fetch_crl()
    crl = x509.load_der_x509_crl(latest_bytes)
    if crl.issuer != ca.subject or not crl.is_signature_valid(ca.public_key()):
        raise RuntimeError("yessign CRL issuer/signature verification failed")
    next_update = crl.next_update.replace(tzinfo=timezone.utc)
    if next_update <= datetime.now(timezone.utc):
        raise RuntimeError("yessign CRL is stale")
    if latest_bytes != current_bytes:
        secret_request(token, CRL_SECRET, ":addVersion", {
            "payload": {"data": base64.b64encode(latest_bytes).decode("ascii")},
        })
        print(f"Fresh issuer-signed CRL published, nextUpdate={next_update.isoformat()}")
    else:
        print(f"Issuer-signed CRL current, nextUpdate={next_update.isoformat()}")


if __name__ == "__main__":
    main()
