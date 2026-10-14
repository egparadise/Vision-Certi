"""Synthetic external-auth CA/client fixtures. NEVER use these as production trust."""
import pathlib, datetime, json
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
root=pathlib.Path(__file__).resolve().parents[1]; out=root/'work/certificate-fixtures'; out.mkdir(exist_ok=True)
if (out/'ca.pem').exists(): raise SystemExit('Synthetic fixtures already exist; preserving trust and client key')
now=datetime.datetime.now(datetime.timezone.utc)
ca_key=ec.generate_private_key(ec.SECP256R1()); doctor_key=ec.generate_private_key(ec.SECP256R1())
name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'INVIZ SYNTHETIC EXTERNAL AUTH TEST CA')])
ca=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=30)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),True).add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()),False).sign(ca_key,hashes.SHA256()))
cert=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'SYNTHETIC DOCTOR - NOT A REAL PERSON')])).issuer_name(name).public_key(doctor_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=7)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.KeyUsage(True,True,False,False,False,False,False,False,False),True).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),False).sign(ca_key,hashes.SHA256()))
crl=(x509.CertificateRevocationListBuilder().issuer_name(name).last_update(now-datetime.timedelta(minutes=1)).next_update(now+datetime.timedelta(days=1)).add_extension(x509.CRLNumber(1),False).sign(ca_key,hashes.SHA256()))
for file,obj in [('ca.pem',ca),('doctor.pem',cert),('ca.crl.pem',crl)]: (out/file).write_bytes(obj.public_bytes(serialization.Encoding.PEM))
(out/'doctor.der').write_bytes(cert.public_bytes(serialization.Encoding.DER))
(out/'doctor.key.pem').write_bytes(doctor_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
# Deliberately retain CA key only locally for revocation tests; backend config reads public files only.
(out/'ca.key.pem').write_bytes(ca_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
(out/'fixture.json').write_text(json.dumps({'fingerprintSha256':cert.fingerprint(hashes.SHA256()).hex(),'synthetic':True}))
print('Synthetic external CA, fresh CRL and local-only client private key created; no secrets printed')