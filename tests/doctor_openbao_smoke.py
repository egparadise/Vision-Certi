"""Live local integration: real OpenBao private PKI + Transit, explicitly synthetic identity."""
import argparse, json, uuid, hashlib, pathlib, urllib.request, urllib.error
from dev_smoke import synthetic_pdf
p=argparse.ArgumentParser(); p.add_argument('--base',default='http://127.0.0.1:18080'); p.add_argument('--pc-fixture'); args=p.parse_args()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({})); checks=[]
def call(method,path,body=None,*,admin=False,token=None,expected=200,ctype=None,key=None):
 headers={}
 if admin: headers.update({'X-Actor-Id':'admin-demo','X-Tenant-Id':'demo-hospital-a'})
 if token: headers['Authorization']='Bearer '+token
 if key: headers['Idempotency-Key']=key
 if isinstance(body,dict): body=json.dumps(body).encode(); ctype='application/json'
 if ctype: headers['Content-Type']=ctype
 req=urllib.request.Request(args.base+path,data=body,headers=headers,method=method)
 try:
  with opener.open(req,timeout=30) as r: status=r.status; raw=r.read(); typ=r.headers.get('Content-Type','')
 except urllib.error.HTTPError as e: status=e.code; raw=e.read(); typ=e.headers.get('Content-Type','')
 result=json.loads(raw) if 'json' in typ else raw
 assert status==expected,(path,status,expected,result)
 checks.append({'path':path,'status':status})
 return result
config=call('GET','/v1/doctor-activation/config'); assert config['signingBackend']=='OPENBAO_TRANSIT'
run=uuid.uuid4().hex; email='synthetic-'+run+'@example.invalid'
created=call('POST','/v1/admin/doctors',{'name':'Synthetic Doctor','licenseNumber':str(uuid.uuid4().int)[:18],'centerName':'Synthetic Center','googleEmail':email,'identityEvidenceRef':'synthetic/identity','licenseEvidenceRef':'synthetic/license'},admin=True,expected=201)
doctor=created['doctor']; assert doctor['status']=='PENDING' and doctor['certificate'] is None
if config.get('activationPolicy')=='CERTIFICATE_REQUIRED':
 challenge=call('POST','/v1/certificate-enrollment/challenges',{'invitationCode':created['invitationCode']},expected=201)
 if args.pc_fixture:
  import base64
  from cryptography.hazmat.primitives import hashes,serialization
  from cryptography.hazmat.primitives.asymmetric import ec
  fixture=pathlib.Path(args.pc_fixture)
  local_key=serialization.load_pem_private_key((fixture/'doctor.key.pem').read_bytes(),password=None)
  proof={'registrationId':challenge['registrationId'],'certificate':base64.b64encode((fixture/'doctor.der').read_bytes()).decode(),'chain':[],'signature':base64.b64encode(local_key.sign(base64.b64decode(challenge['signedPayloadBase64']),ec.ECDSA(hashes.SHA256()))).decode(),'signatureAlgorithm':'SHA256withECDSA'}
  verified=call('POST','/v1/certificate-enrollment/verify',proof,expected=201)
  call('POST','/v1/certificate-enrollment/verify',proof,expected=409)
 else:
  verified=call('POST','/v1/certificate-enrollment/dev/mock-sign',{'challengeId':challenge['challengeId'],'fingerprintSha256':None},expected=201)
 token=verified['activationToken']; enrollment=verified['enrollment']; reg=enrollment['registrationId']
 call('POST','/v1/doctor-activation/activate',{},token=token,expected=409)
 call('POST',f'/v1/certificate-enrollment/enrollments/{reg}/consent',{'version':'2026-10-13-draft','authentication':True,'signature':True,'privacy':True},token=token)
 approved=call('POST',f"/v1/admin/doctors/{doctor['id']}/certificate-reviews",{'registrationId':reg,'decision':'APPROVE','fingerprintSha256':enrollment['fingerprintSha256'],'evidenceRef':'synthetic/review','note':'Synthetic test only'},admin=True)
 assert approved['registrationStatus']=='ACTIVE'
 if args.pc_fixture:
  login_challenge=call('POST','/v1/certificate-enrollment/login-challenges',{},expected=201)
  login_proof={'challengeId':login_challenge['challengeId'],'fingerprintSha256':enrollment['fingerprintSha256'],'certificate':proof['certificate'],'chain':[],'signature':base64.b64encode(local_key.sign(base64.b64decode(login_challenge['signedPayloadBase64']),ec.ECDSA(hashes.SHA256()))).decode(),'signatureAlgorithm':'SHA256withECDSA'}
  token=call('POST','/v1/certificate-enrollment/login',login_proof)['activationToken']
else:
 challenge=call('POST','/v1/doctor-activation/challenges',{'invitationCode':created['invitationCode']},expected=201)
 verified=call('POST','/v1/doctor-activation/dev-identity',{'challengeId':challenge['challengeId'],'email':email,'subject':'synthetic-'+run})
 token=verified['activationToken']
 call('POST','/v1/doctor-activation/consent',{'version':'2026-10-13-draft','authentication':True,'signature':True,'privacy':True},token=token)
active=call('POST','/v1/doctor-activation/activate',{},token=token)
assert active['status']=='ACTIVE' and active['certificate']['kind']=='X509'
assert 'BEGIN CERTIFICATE' in active['certificate']['certificatePem']
assert call('POST','/v1/doctor-activation/activate',{},token=token)['certificate']['certificateId']==active['certificate']['certificateId']
pdf=synthetic_pdf(); boundary='certi-'+run
metadata=json.dumps({'externalDocumentId':'bao-'+run,'externalStudyId':'synthetic-study','title':'Synthetic OpenBao Test','signerId':doctor['id']})
body=(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+pdf+f'\r\n--{boundary}--\r\n'.encode()
doc=call('POST','/v1/documents',body,admin=True,key='upload-'+run,ctype='multipart/form-data; boundary='+boundary,expected=201)
version=doc['latestVersion']; assert version['sha256']==hashlib.sha256(pdf).hexdigest()
request=call('POST','/v1/sign-requests',{'documentVersionId':version['id'],'signerId':doctor['id']},admin=True,key='request-'+run,expected=201)
if config.get('activationPolicy')=='CERTIFICATE_REQUIRED':
 assert call('GET',f"/v1/doctor/sign-requests/{request['id']}/document",token=token)==pdf
request=call('GET',f"/v1/doctor/sign-requests/{request['id']}",token=token)
approval={'documentSha256':request['documentSha256'],'nonce':request['nonce']}
done=call('POST',f"/v1/doctor/sign-requests/{request['id']}/approve",approval,token=token,expected=202)
assert done['status']=='COMPLETED'
verification=call('POST',f"/v1/signatures/{done['signatureId']}/verify",admin=True)
assert verification['clinicalUseAllowed'] is False and verification['status']=='INDETERMINATE'
call('PATCH',f"/v1/admin/doctors/{doctor['id']}/status",{'status':'REVOKED','reason':'Synthetic integration test completed'},admin=True)
call('GET','/v1/doctor-activation/me',token=token,expected=401)
output={'result':'PASS','identity':'SYNTHETIC_ONLY','provider':'REAL_OPENBAO_2.7.1','clinicalUseAllowed':False,'doctorId':doctor['id'],'certificateId':active['certificate']['certificateId'],'signatureId':done['signatureId'],'checks':checks,'verification':verification}
pathlib.Path('work/doctor-openbao-smoke-result.json').write_text(json.dumps(output,indent=2))
print(f'PASS {len(checks)} HTTP checks: real OpenBao key/CSR/X509, signed report, private trust stays INDETERMINATE, revocation blocks session')