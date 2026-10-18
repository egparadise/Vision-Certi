"""Run local dev backend with OpenBao service credentials kept out of command-line/log output."""
import argparse, json, os, pathlib, subprocess
p=argparse.ArgumentParser(); p.add_argument('--port',type=int,default=8080); p.add_argument('--jar'); p.add_argument('--mock-identity',action='store_true'); p.add_argument('--mock-certificate',action='store_true')
p.add_argument('--certificate-trust'); p.add_argument('--certificate-crl')
p.add_argument('--integration',action='store_true'); p.add_argument('--database',action='store_true')
a=p.parse_args(); root=pathlib.Path(__file__).resolve().parents[1]
config=json.loads((root/'work/openbao/runtime.secrets.json').read_text())
env={k.upper():v for k,v in os.environ.items()}
env.update(VISIONCERTI_SIGNING_BACKEND='OPENBAO_TRANSIT',VISIONCERTI_OPENBAO_ADDR=config['address'],VISIONCERTI_OPENBAO_PROVISIONING_TOKEN=config['provisioningToken'],VISIONCERTI_OPENBAO_SIGNING_TOKEN=config['signingToken'],VISIONCERTI_DOCTOR_DEV_IDENTITY_ENABLED=str(a.mock_identity).lower(),VISIONCERTI_CERT_DEV_MOCK_ENABLED=str(a.mock_certificate).lower())
admin_password_file = root/'work/admin-login.secret'
if admin_password_file.is_file():
 env['VISIONCERTI_DEV_ADMIN_PASSWORD'] = admin_password_file.read_text(encoding='utf-8').strip()
if a.certificate_trust: env['VISIONCERTI_CERT_TRUST_ANCHORS_FILE']=str(pathlib.Path(a.certificate_trust).resolve())
if a.certificate_crl: env['VISIONCERTI_CERT_CRL_FILES']=str(pathlib.Path(a.certificate_crl).resolve())
if a.integration:
 env.update(VISIONCERTI_PACS_CLIENT_ID='pacs-sv70',VISIONCERTI_PACS_TENANT_IDS='demo-hospital-a',VISIONCERTI_PACS_CLIENT_SECRET_FILE=str(root/'work/sv70-integration.secret'))
if a.database:
 db=json.loads((root/'work/postgres/runtime-connection.json').read_text())
 env.update(VISIONCERTI_PERSISTENCE_MODE='POSTGRESQL',VISIONCERTI_DB_URL=db['url'],VISIONCERTI_DB_USERNAME=db['username'],VISIONCERTI_DB_PASSWORD_FILE=db['passwordFile'])
jar=pathlib.Path(a.jar) if a.jar else root/'apps/backend/build/libs/vision-certi-backend-0.1.0-dev.jar'
child=subprocess.Popen(['java','-jar',str(jar),'--spring.profiles.active=dev','--server.address=127.0.0.1',f'--server.port={a.port}'],env=env)
try: raise SystemExit(child.wait())
except KeyboardInterrupt: child.terminate(); child.wait()
