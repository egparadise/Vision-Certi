"""SYNTHETIC doctor certificate tool for manual PACS testing (stands in for a PC certificate SDK). Test CA only.

  issue <slug> [--name "CN"]   new P-256 key + certificate from the synthetic test CA
                               -> work/certificate-fixtures/doctors/<slug>/{cert.pem,key.pem}
  sign  <slug> <challenge>     sign a downloaded challenge file (or its Base64 'signedPayloadBase64' text) and print the
                               Base64 signature for the INVIZ popup; also copied to the Windows clipboard.

The private key never leaves this PC (the popup only receives the public certificate and the signature).
"""
import argparse
import base64
import datetime
import hashlib
import pathlib
import subprocess

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

root = pathlib.Path(__file__).resolve().parents[1]
fixtures = root / 'work/certificate-fixtures'


def folder(slug: str) -> pathlib.Path:
    if not slug.replace('-', '').replace('_', '').isalnum():
        raise SystemExit('slug must be letters, digits, - or _')
    return fixtures / 'doctors' / slug


def issue(slug: str, name: str) -> None:
    out = folder(slug)
    if (out / 'key.pem').exists():
        raise SystemExit(f'{out} already exists; keep the enrolled key (delete the folder to re-issue)')
    ca = x509.load_pem_x509_certificate((fixtures / 'ca.pem').read_bytes())
    ca_key = serialization.load_pem_private_key((fixtures / 'ca.key.pem').read_bytes(), None)
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)]))
            .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(ca.not_valid_after_utc)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(True, True, False, False, False, False, False, False, False), critical=True)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
            .sign(ca_key, hashes.SHA256()))
    out.mkdir(parents=True)
    (out / 'cert.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (out / 'key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                    serialization.NoEncryption()))
    der = cert.public_bytes(serialization.Encoding.DER)
    print(f'issued {name!r} -> {out}')
    print(f'fingerprintSha256={hashlib.sha256(der).hexdigest()} notAfter={cert.not_valid_after_utc.isoformat()}')


def sign(slug: str, challenge: str, clip: bool = True) -> str:
    key = serialization.load_pem_private_key((folder(slug) / 'key.pem').read_bytes(), None)
    path = pathlib.Path(challenge)
    payload = path.read_bytes() if path.is_file() else base64.b64decode(challenge, validate=True)
    signature = base64.b64encode(key.sign(payload, ec.ECDSA(hashes.SHA256()))).decode()
    note = ''
    if clip:
        try:
            subprocess.run(['clip'], input=signature.encode(), check=True)
            note = ' (copied to clipboard)'
        except (OSError, subprocess.CalledProcessError):
            pass
    print(f'signatureAlgorithm=SHA256withECDSA{note}')
    print(signature)
    return signature


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('issue')
    p.add_argument('slug')
    p.add_argument('--name', default=None)
    p = sub.add_parser('sign')
    p.add_argument('slug')
    p.add_argument('challenge', help='downloaded challenge-*.bin file, or signedPayloadBase64 text')
    p.add_argument('--no-clip', action='store_true', help='do not copy to the clipboard (scripts)')
    args = parser.parse_args()
    if args.cmd == 'issue':
        issue(args.slug, args.name or f'SYNTHETIC DOCTOR {args.slug} - NOT A REAL PERSON')
    else:
        sign(args.slug, args.challenge, clip=not args.no_clip)
