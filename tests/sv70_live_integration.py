"""Real-network Java/OpenBao + real MariaDB PACS integration, synthetic identities/documents only.

Requires reviewed MariaDB migration, VC configured with work/sv70-integration.secret and test CA/CRL.
No fake transport or signer: the Python PACS service calls Java over HTTP, and Java signs in OpenBao.
The certificate private key is generated and used on this test client, never uploaded to the server.
"""
import argparse
import base64
from datetime import datetime,timedelta,timezone
import hashlib
import json
from pathlib import Path
import secrets
import sys
import urllib.error
import urllib.request
import uuid

from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from sqlalchemy import create_engine,text
from sqlalchemy.engine import URL
from sqlalchemy.orm import sessionmaker

ROOT=Path(__file__).resolve().parents[1]
PACS=ROOT/'SaintViewPACS-handover-full-20260904/SaintViewPACS-handover-full-20260721/05-server-source/extracted/webpacs_api'
sys.path.insert(0,str(PACS))
from app.integrations.vision_certi.config import load_config
from app.integrations.vision_certi.client import VisionCertiClient
from app.integrations.vision_certi.legacy_port import SqlLegacyPort
from app.integrations.vision_certi.service import VcIntegrationService,VcError
from app.integrations.vision_certi.snapshot import content_sha256

parser=argparse.ArgumentParser()
parser.add_argument('--base',default='http://127.0.0.1:18081')
parser.add_argument('--staff-token-file')
args=parser.parse_args()
assert args.base.startswith(('http://127.0.0.1:','https://127.0.0.1:')),'Local verification only'
checks=[]
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
staff_token=Path(args.staff_token_file).read_text().strip() if args.staff_token_file else None

def api(method,path,body=None,*,admin=False,token=None,expected=200):
    headers={}
    if admin:
        if staff_token: headers['Authorization']='Bearer '+staff_token
        else: headers.update({'X-Actor-Id':'admin-demo','X-Tenant-Id':'demo-hospital-a'})
    if token: headers['Authorization']='Bearer '+token
    if body is not None:
        body=json.dumps(body).encode()
        headers['Content-Type']='application/json'
    request=urllib.request.Request(args.base+path,data=body,headers=headers,method=method)
    try:
        with opener.open(request,timeout=30) as response:
            status=response.status; raw=response.read(); kind=response.headers.get('Content-Type','')
    except urllib.error.HTTPError as e:
        status=e.code;raw=e.read();kind=e.headers.get('Content-Type','')
    result=json.loads(raw) if 'json' in kind else raw
    assert status==expected,(path,status,expected,result)
    return result

cfg=json.loads((ROOT/'work/sv70-mariadb/test-connection.json').read_text())
assert (cfg['host'],cfg['port'],cfg['database'])==('127.0.0.1',13316,'sv70_certi_test')
engine=create_engine(URL.create('mysql+pymysql',username=cfg['user'],password=Path(cfg['passwordFile']).read_text().strip(),
    host=cfg['host'],port=cfg['port'],database=cfg['database'],query={'charset':'utf8mb4'}))
Session=sessionmaker(engine,expire_on_commit=False)
number=secrets.randbelow(100_000_000)+10_000
with engine.begin() as db:
    # Minimal legacy shape on this isolated DB only. No production schema or settings are read.
    db.execute(text('CREATE TABLE IF NOT EXISTS pacs_users_view(user_idx INT PRIMARY KEY,user_id VARCHAR(100),user_name VARCHAR(100),user_status CHAR(1),user_type VARCHAR(10),group_level INT,doctor_idx INT,doctor_license_number VARCHAR(100))'))
    db.execute(text('ALTER TABLE pacs_users_view ADD COLUMN IF NOT EXISTS center_list TEXT'))
    db.execute(text('CREATE TABLE IF NOT EXISTS pacs_users(user_idx INT PRIMARY KEY,user_report_edit_all CHAR(1))'))
    db.execute(text('CREATE TABLE IF NOT EXISTS pacs_study(study_idx INT PRIMARY KEY,user_idx INT,study_status VARCHAR(10),study_accession_no VARCHAR(100),patient_id VARCHAR(100),patient_name VARCHAR(100),study_modality VARCHAR(20),study_body_part VARCHAR(30),study_description VARCHAR(100),study_date VARCHAR(30),hospital_idx INT,hospital_name VARCHAR(100))'))
    db.execute(text('CREATE TABLE IF NOT EXISTS pacs_study_report(report_idx INT PRIMARY KEY,study_idx INT UNIQUE,report_status VARCHAR(10),report_reading LONGTEXT,report_conclusion LONGTEXT,report_study_comment VARCHAR(255),report_refer_comment VARCHAR(255),report_cvr_send CHAR(1),report_create_idx INT,report_open_edit CHAR(1),report_approve_idx INT,report_approve_datetime DATETIME(6),report_update_datetime DATETIME(6))'))
    for user,admin in [(number,False),(number+1,True)]:
        db.execute(text("INSERT INTO pacs_users_view(user_idx,user_id,user_name,user_status,user_type,group_level,doctor_idx,doctor_license_number,center_list) VALUES(:u,:i,'Synthetic User','A','P',:g,:d,'SYNTHETIC-LICENSE',:centers)"),{'u':user,'i':'synthetic-'+str(user),'g':99 if admin else 10,'d':None if admin else user,'centers':json.dumps([{'center_name':'Synthetic PACS'}])})
        db.execute(text("INSERT INTO pacs_users VALUES(:u,'N')"),{'u':user})
    db.execute(text("INSERT INTO pacs_study VALUES(:n,:n,'R','SYNTHETIC','SYNTHETIC','합성 환자','CT','CHEST','Synthetic chest','2026-10-14',1,'Synthetic Hospital')"),{'n':number})
    db.execute(text("INSERT INTO pacs_study_report(report_idx,study_idx,report_status,report_reading,report_conclusion,report_create_idx,report_cvr_send,report_open_edit) VALUES(:n,:n,'R','합성 판독문 원문','검증용 결론',:n,'N','N')"),{'n':number})

vcconfig=load_config({'VISION_CERTI_INTEGRATION_ENABLED':'true','VISION_CERTI_BASE_URL':args.base,
    'VISION_CERTI_ALLOW_LOCALHOST_HTTP':'true','VISION_CERTI_CLIENT_ID':'pacs-sv70',
    'VISION_CERTI_CLIENT_SECRET_FILE':str(ROOT/'work/sv70-integration.secret'),
    'VISION_CERTI_TENANT_ID':'demo-hospital-a','VISION_CERTI_LINK_ADMIN_USER_IDX':str(number+1)})
assert vcconfig.operational,vcconfig.problems
port=SqlLegacyPort(None)
client=VisionCertiClient(vcconfig)
service=VcIntegrationService(vcconfig,client,port)
def invoke(fn,*pos,**kw):
    with Session() as main,Session() as manage:
        return fn(main,manage,*pos,**kw)

run=uuid.uuid4().hex
with engine.begin() as db:
    db.execute(text('UPDATE pacs_users_view SET doctor_license_number=:license WHERE user_idx=:u'),
               {'license': str(int(run,16))[:20], 'u': number})
assert invoke(service.enrollment_status,number)['registrationStatus']=='NOT_REGISTERED'
try: invoke(service.require_enrolled_pacs_access,number)
except VcError as error: assert error.code=='VC_SESSION_REQUIRED'
else: raise AssertionError('Unregistered doctor admitted')
started=invoke(service.enrollment_start,number)
doctor=started['doctorId']
challenge=started['challenge']
assert invoke(service.enrollment_status,number)['registrationStatus']=='PENDING'
checks.append('first-login account binding from PACS profile without admin preregistration or invitation')
fixture=ROOT/'work/certificate-fixtures'
ca=x509.load_pem_x509_certificate((fixture/'ca.pem').read_bytes())
ca_key=serialization.load_pem_private_key((fixture/'ca.key.pem').read_bytes(),None)
local_key=ec.generate_private_key(ec.SECP256R1())
now=datetime.now(timezone.utc)
cert=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Synthetic SV70 '+run)]))
 .issuer_name(ca.subject).public_key(local_key.public_key()).serial_number(x509.random_serial_number())
 .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=1))
 .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
 .add_extension(x509.KeyUsage(True,False,False,False,False,False,False,False,False),critical=True)
 .sign(ca_key,hashes.SHA256()))
der=cert.public_bytes(serialization.Encoding.DER)
def proof(challenge,login=False):
    result={'certificate':base64.b64encode(der).decode(),'chain':[],
        'signature':base64.b64encode(local_key.sign(base64.b64decode(challenge['signedPayloadBase64']),ec.ECDSA(hashes.SHA256()))).decode(),
        'signatureAlgorithm':'SHA256withECDSA'}
    result['challengeId' if login else 'registrationId']=challenge['challengeId' if login else 'registrationId']
    if login: result['fingerprintSha256']=hashlib.sha256(der).hexdigest()
    return result
verified=api('POST','/v1/certificate-enrollment/verify',proof(challenge),expected=201)
token=verified['activationToken']; reg=verified['enrollment']['registrationId']
assert verified['enrollment']['medicalCredentialsRequired'] is True
medical=api('PUT',f'/v1/certificate-enrollment/enrollments/{reg}/medical-credentials',
    {'medicalLicenseNumber':verified['doctor']['licenseNumber'],'specialist':True,
     'specialistQualificationNumber':'SYNTHETIC-123','specialty':'Synthetic radiology'},token=token)
assert medical['reviewStatus']=='PENDING' and medical['medicalCredentialsSubmittedAt']
checks.append('medical license and specialist qualification saved as pending review, not automatically verified')
api('POST',f'/v1/certificate-enrollment/enrollments/{reg}/consent',{'version':'2026-10-13-draft','authentication':True,'signature':True,'privacy':True},token=token)
api('POST',f'/v1/admin/doctors/{doctor}/certificate-reviews',{'registrationId':reg,'decision':'APPROVE','fingerprintSha256':hashlib.sha256(der).hexdigest(),'evidenceRef':'synthetic/local-proof-review','medicalCredentialsSubmittedAt':medical['medicalCredentialsSubmittedAt']},admin=True)
active=api('POST','/v1/doctor-activation/activate',{},token=token)
assert active['status']=='ACTIVE' and active['certificate']['kind']=='X509'
assert invoke(service.enrollment_status,number)['registrationStatus']=='ACTIVE'
try: invoke(service.require_enrolled_pacs_access,number,'Bearer legacy-password-session')
except VcError as error: assert error.code=='VC_SESSION_REQUIRED'
else: raise AssertionError('Password-only session admitted an enrolled doctor')
checks.append('active enrollment still rejects password-only PACS access')
checks.append('actual challenge proof, trusted synthetic CA and CRL, doctor consent, real OpenBao X509 activation')
challenge=api('POST','/v1/certificate-enrollment/login-challenges',{},expected=201)
token=api('POST','/v1/certificate-enrollment/login',proof(challenge,True))['activationToken']
handoff=api('POST','/v1/doctor/pacs-handoffs',{'audience':'pacs-sv70'},token=token,expected=201)
pacs_token,session,user=invoke(service.certificate_login,handoff['handoffCode'],'127.0.0.1')
try: invoke(service.certificate_login,handoff['handoffCode'],'127.0.0.1')
except VcError as error: assert error.status==401
else: raise AssertionError('Handoff replay accepted')
principal=invoke(service.resolve_session,'Bearer '+pacs_token)
invoke(service.require_enrolled_pacs_access,number,'Bearer '+pacs_token)
checks.append('fresh certificate login required and valid certificate session permits PACS access')
checks.append('server-to-server HMAC handoff redemption, replay rejection, self-enrolled mapping, hashed PACS session')
with Session() as db: expected=content_sha256(port.get_report_for_study(db,number))
approval,_=invoke(service.prepare,principal,number,expected)
request=api('GET',f'/v1/doctor/sign-requests/{approval.vc_sign_request_id}',token=token)
document=api('GET',f'/v1/doctor/sign-requests/{approval.vc_sign_request_id}/document',token=token)
assert document==json.loads(approval.snapshot_json)  # JSON response parsed, exact digest checked separately below
assert request['documentSha256']==hashlib.sha256(approval.snapshot_json.encode()).hexdigest()
api('POST',f'/v1/doctor/sign-requests/{approval.vc_sign_request_id}/approve',{'documentSha256':request['documentSha256'],'nonce':request['nonce']},token=token,expected=422)
done=api('POST',f'/v1/doctor/sign-requests/{approval.vc_sign_request_id}/approve',{'documentSha256':request['documentSha256'],'nonce':request['nonce'],'reviewAttestation':'REVIEWED_EXACT_REPORT_CONTENT'},token=token,expected=202)
assert done['status']=='COMPLETED'
approval,status=invoke(service.finalize,approval.approval_id,principal=principal)
assert status==200 and approval.state=='APPROVED'
checks.append('server-built JSON snapshot, explicit report approval, actual OpenBao signature verified by PACS')
with engine.connect() as db:
    assert db.execute(text('SELECT report_status FROM pacs_study_report WHERE report_idx=:n'),{'n':number}).scalar()=='A'
engine.dispose()
# Recreate service/DB sessions to verify durable PACS identity and approval evidence.
service=VcIntegrationService(vcconfig,VisionCertiClient(vcconfig),SqlLegacyPort(None))
assert invoke(service.resolve_session,'Bearer '+pacs_token).doctor_id==doctor
again,status=invoke(service.finalize,approval.approval_id,principal=principal)
assert status==200 and again.vc_signature_id==approval.vc_signature_id
checks.append('database reconnect preserves session, immutable evidence, and idempotent finalize')
output={'result':'PASS','systems':['REAL_JAVA_HTTP','REAL_MARIADB','REAL_OPENBAO_TRANSIT'],
        'identity':'SYNTHETIC_TEST_CA_PC_SIGNATURE','checks':checks,'approvalId':approval.approval_id,'signatureId':approval.vc_signature_id}
(ROOT/'work/sv70-live-result.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'result':'PASS','checks':checks},ensure_ascii=False))
