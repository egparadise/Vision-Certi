"""Re-issue the SYNTHETIC test CA's CRL (empty revocation list) so certificate checks stop reporting STALE.

Test fixtures only — never production trust. The backend re-reads CRL files on every call, so no restart is needed.
Validity is capped by the CA's own expiry. Usage: work/certificate-tools/Scripts/python.exe tests/refresh_test_crl.py
"""
import argparse
import datetime
import pathlib

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization

root = pathlib.Path(__file__).resolve().parents[1]
fixtures = root / 'work/certificate-fixtures'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--days', type=int, default=7, help='nextUpdate horizon (default 7, capped by CA expiry)')
args = parser.parse_args()

ca = x509.load_pem_x509_certificate((fixtures / 'ca.pem').read_bytes())
ca_key = serialization.load_pem_private_key((fixtures / 'ca.key.pem').read_bytes(), None)
old = x509.load_pem_x509_crl((fixtures / 'ca.crl.pem').read_bytes())
number = old.extensions.get_extension_for_class(x509.CRLNumber).value.crl_number + 1
now = datetime.datetime.now(datetime.timezone.utc)
next_update = min(now + datetime.timedelta(days=args.days), ca.not_valid_after_utc)
builder = (x509.CertificateRevocationListBuilder().issuer_name(ca.subject)
           .last_update(now - datetime.timedelta(minutes=1)).next_update(next_update)
           .add_extension(x509.CRLNumber(number), False))
for revoked in old:  # keep any revocations made for tests
    builder = builder.add_revoked_certificate(revoked)
crl = builder.sign(ca_key, hashes.SHA256())
(fixtures / 'ca.crl.pem').write_bytes(crl.public_bytes(serialization.Encoding.PEM))
print(f'CRL #{number} issued; nextUpdate={next_update.isoformat()} (CA expires {ca.not_valid_after_utc.isoformat()})')
