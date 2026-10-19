"""Start opt-in HTTPS LAN/Tailscale UI on port 5184, proxying the loopback test backend on 18082.

Requires cryptography and apps/web/node_modules. Keeps all keys under ignored work/lan-test.
The public CA certificate must be trusted separately on each test client. Never installs trust silently.
"""
import argparse
from datetime import datetime, timedelta, timezone
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import urllib.request

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--address', required=True, help='This PC RFC1918 or Tailscale IPv4 address')
    args = parser.parse_args()
    address = ipaddress.IPv4Address(args.address)
    if not any(address in ipaddress.ip_network(cidr) for cidr in
               ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '100.64.0.0/10')):
        parser.error('Use an RFC1918 LAN or Tailscale IPv4 address')
    with socket.socket() as probe:
        probe.bind((str(address), 5184))  # verify ownership and reject an occupied port
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open('http://127.0.0.1:18082/v1/runtime/capabilities', timeout=5) as response:
        capabilities = json.load(response)
    if capabilities['persistence']['mode'] != 'POSTGRESQL':
        raise RuntimeError('Expected the persistent local test backend on 18082')

    folder = ROOT / 'work/lan-test'
    private = folder / 'private'
    public = folder / 'public'
    private.mkdir(parents=True, exist_ok=True)
    public.mkdir(parents=True, exist_ok=True)
    # LAN Vite serves this isolated directory so only the public CA and approved UI assets are exposed.
    for filename in ('inviz-logo.jpg', 'inviz-logo-transparent.png'):
        logo = ROOT / 'apps/web/public' / filename
        if logo.is_file():
            shutil.copy2(logo, public / filename)
    if os.name == 'nt':
        from local_database import protect
        protect(private)
    ca_key_file = private / 'ca.key.pem'
    ca_file = public / 'vision-certi-lan-ca.cer'
    now = datetime.now(timezone.utc)
    if ca_key_file.exists() != ca_file.exists():
        raise RuntimeError('Incomplete CA files; restore the matching pair before starting')
    if not ca_key_file.exists():
        ca_key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Vision-Certi LAN TEST CA')])
        ca = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(ca_key.public_key())
              .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5))
              .not_valid_after(now+timedelta(days=90))
              .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
              .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
              .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
              .sign(ca_key, hashes.SHA256()))
        ca_key_file.write_bytes(ca_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    ca_key = serialization.load_pem_private_key(ca_key_file.read_bytes(), password=None)
    ca = x509.load_pem_x509_certificate(ca_file.read_bytes())
    # Upgrade the initial local test CA before distributing it to clients.
    try:
        ca.extensions.get_extension_for_class(x509.SubjectKeyIdentifier)
    except x509.ExtensionNotFound:
        builder = (x509.CertificateBuilder().subject_name(ca.subject).issuer_name(ca.issuer)
                   .public_key(ca_key.public_key()).serial_number(ca.serial_number)
                   .not_valid_before(ca.not_valid_before_utc).not_valid_after(ca.not_valid_after_utc))
        for extension in ca.extensions:
            builder = builder.add_extension(extension.value, extension.critical)
        ca = builder.add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), False).sign(ca_key, hashes.SHA256())
        ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    if ca.not_valid_after_utc <= now+timedelta(days=1):
        raise RuntimeError('LAN CA near expiry; rotate it and update client trust explicitly')
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, str(address))]))
            .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-timedelta(minutes=5)).not_valid_after(min(now+timedelta(days=30), ca.not_valid_after_utc))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
            .add_extension(x509.KeyUsage(True, False, True, False, False, False, False, False, False), critical=True)
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(address)]), critical=False)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    key_file, cert_file = private/'server.key.pem', private/'server.cert.pem'
    key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    env = dict(os.environ)
    env.update(VISIONCERTI_LAN_HOST=str(address), VISIONCERTI_LAN_CERT_FILE=str(cert_file),
               VISIONCERTI_LAN_KEY_FILE=str(key_file), VISIONCERTI_LAN_PUBLIC_DIR=str(public))
    node = shutil.which('node')
    if not node:
        raise RuntimeError('Node.js is required')
    print(f'LAN URL: https://{address}:5184/#/login', flush=True)
    print(f'Client trust certificate (public only): {ca_file}', flush=True)
    child = subprocess.Popen([node, str(ROOT/'apps/web/node_modules/vite/bin/vite.js')], cwd=ROOT/'apps/web', env=env)
    (folder/'server.pid').write_text(str(child.pid), encoding='ascii')
    try:
        raise SystemExit(child.wait())
    except KeyboardInterrupt:
        child.terminate()
        child.wait()


if __name__ == '__main__':
    main()
