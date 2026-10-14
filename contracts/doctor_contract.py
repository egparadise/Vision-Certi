"""Extend the development API with certificate-required physician enrollment."""
PUBLIC_PATHS = set()
def extend(spec):
    schemas=spec['components']['schemas']; paths=spec['paths']
    S={'type':'string'}; U={'type':'string','format':'uuid'}; B={'type':'boolean'}
    def ref(name): return {'$ref':'#/components/schemas/'+name}
    def obj(fields,required=None): return {'type':'object','properties':fields,'required':list(fields) if required is None else required}
    def listed(name): return obj({'items':{'type':'array','items':ref(name)}})
    schemas['Doctor']=obj({'id':U,'tenantId':S,'name':S,'licenseNumber':S,'centerName':S,'status':{'enum':['PENDING','IDENTITY_VERIFIED','CONSENTED','ACTIVE','SUSPENDED','REVOKED']},'certificate':{'type':['object','null']}},['id','tenantId','status'])
    schemas['ExternalEnrollment']=obj({'registrationId':U,'doctorId':U,'kind':S,'fingerprintSha256':S,'publicKeySha256':{'type':['string','null']},'registrationStatus':{'enum':['PENDING','ACTIVE','REVOKED']},'externalStatus':{'enum':['GOOD','REVOKED','UNKNOWN','STALE']},'reviewStatus':{'enum':['PENDING','APPROVED','REJECTED']}})
    schemas['DoctorSession']=obj({'activationToken':S,'expiresAt':S,'identityMode':S,'doctor':ref('Doctor')})
    schemas['CertificateSession']=obj({'activationToken':S,'expiresAt':S,'identityMode':S,'doctor':ref('Doctor'),'enrollment':ref('ExternalEnrollment')})
    schemas['DoctorConsent']=obj({'version':{'const':'2026-10-13-draft'},'authentication':{'const':True},'signature':{'const':True},'privacy':{'const':True}})
    schemas['CertificateChallenge']=obj({'challengeId':U,'registrationId':{'type':['string','null']},'purpose':S,'serviceId':S,'nonce':S,'expiresAt':S,'signedPayload':S,'signedPayloadBase64':S})
    proof={'certificate':S,'chain':{'type':'array','items':S,'maxItems':5},'signature':S,'signatureAlgorithm':{'enum':['SHA256withRSA','SHA256withECDSA']}}
    schemas['CertificateProof']=obj({'registrationId':U,**proof},['registrationId','certificate','signature','signatureAlgorithm'])
    schemas['CertificateLoginProof']=obj({'challengeId':U,'fingerprintSha256':S,**proof},['challengeId','fingerprintSha256','certificate','signature','signatureAlgorithm'])
    schemas['PreregisterDoctor']=obj({k:S for k in ['name','licenseNumber','centerName','googleEmail','identityEvidenceRef','licenseEvidenceRef','expectedCertificateFingerprint']},['name','licenseNumber','centerName','googleEmail','identityEvidenceRef','licenseEvidenceRef'])
    schemas['DoctorInvitation']=obj({'doctor':ref('Doctor'),'invitationCode':S,'invitationExpiresAt':S})
    spec['components']['securitySchemes']['DoctorSession']={'type':'http','scheme':'bearer','description':'Opaque physician session. Dev staff headers cannot substitute.'}
    for name in ['Signature','SignRequest','Verification']: schemas[name]['properties']['signatureMode']=S
    schemas['Signature']['properties']['doctorEvidence']={'type':['object','null'],'description':'Doctor, authentication enrollment, certificate, approval, exact report hash, key version, audit references'}
    def add(path,method,name,response=None,body=None,auth='doctor',status=200,description=''):
        security=[] if auth=='public' else ([{'DevTenant':[],'DevActor':[]}] if auth=='admin' else [{'DoctorSession':[]}])
        if auth=='public': PUBLIC_PATHS.add(path)
        op={'operationId':name,'security':security,'parameters':[],'description':description,'responses':{
            str(status):{'description':'Development response','content':{'application/json':{'schema':response or {'type':'object'}}}},
            'default':{'description':'Fail closed; full errors in backend API Markdown','content':{'application/json':{'schema':ref('Error')}}}}}
        for part in path.split('/'):
            if part.startswith('{'): op['parameters'].append({'in':'path','name':part[1:-1],'required':True,'schema':U})
        if body is not None: op['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
        paths.setdefault(path,{})[method]=op
        return op
    a='/v1/admin/doctors'; d='/v1/doctor-activation'; c='/v1/certificate-enrollment'
    add(a,'get','adminListDoctors',listed('Doctor'),auth='admin')
    add(a,'post','adminPreregisterDoctor',ref('DoctorInvitation'),ref('PreregisterDoctor'),'admin',201,'No key or certificate; invitation code returned once')
    add(a+'/{id}','get','adminGetDoctor',ref('Doctor'),auth='admin')
    add(a+'/{id}/invitation','post','adminReissueInvitation',ref('DoctorInvitation'),auth='admin',status=201)
    add(a+'/{id}/status','patch','adminDoctorStatus',ref('Doctor'),obj({'status':{'enum':['SUSPENDED','REVOKED']},'reason':S}),'admin')
    add(a+'/{id}/certificate-enrollments','get','adminCertificateEnrollments',listed('ExternalEnrollment'),auth='admin')
    add(a+'/{id}/certificate-reviews','post','adminCertificateReview',ref('ExternalEnrollment'),obj({'registrationId':U,'decision':{'enum':['APPROVE','REJECT']},'fingerprintSha256':S,'evidenceRef':S,'note':S},['registrationId','decision','fingerprintSha256','evidenceRef']),'admin')
    add(d+'/config','get','doctorActivationConfig',auth='public')
    for path,name,body in [('challenges','googleChallenge',obj({'invitationCode':S})),('login-challenges','googleLoginChallenge',obj({}))]:
        add(d+'/'+path,'post',name,body=body,auth='public',status=201)
    for path,name,body in [('google','googleProof',obj({'challengeId':U,'idToken':S})),('dev-identity','mockIdentity',obj({'challengeId':U,'email':S,'subject':S}))]:
        add(d+'/'+path,'post',name,ref('DoctorSession'),body,'public')
    add(d+'/consent','post','doctorConsent',ref('Doctor'),ref('DoctorConsent'))
    add(d+'/activate','post','doctorActivate',ref('Doctor'),obj({}),description='Default CERTIFICATE_REQUIRED needs ACTIVE external enrollment and certificate-authenticated session')
    add(d+'/me','get','doctorMe')
    add('/v1/doctor/sign-requests','get','physicianRequests',listed('SignRequest'))
    add('/v1/doctor/sign-requests/{id}','get','physicianRequest',ref('SignRequest'))
    add('/v1/doctor/sign-requests/{id}/approve','post','physicianApprove',ref('SignRequest'),ref('ApproveSignRequest'),status=202)
    op=add('/v1/doctor/sign-requests/{id}/document','get','physicianDocument')
    op['responses']['200']={'description':'Exact PDF, X-Document-Sha256, no-store','content':{'application/pdf':{'schema':{'type':'string','format':'binary'}}}}
    add(c+'/config','get','certificateConfig',auth='public')
    op=add(c+'/challenges','post','certificateChallenge',ref('CertificateChallenge'),obj({'invitationCode':S},[]),'public',201,'Invitation or existing certificate session for renewal')
    op['security']=[{}, {'DoctorSession':[]}]
    add(c+'/login-challenges','post','certificateLoginChallenge',ref('CertificateChallenge'),obj({}),'public',201)
    add(c+'/verify','post','certificateProof',ref('CertificateSession'),ref('CertificateProof'),'public',201)
    add(c+'/login','post','certificateLogin',ref('CertificateSession'),ref('CertificateLoginProof'),'public')
    add(c+'/enrollments','get','certificateHistory',listed('ExternalEnrollment'))
    add(c+'/enrollments/{id}/consent','post','certificateConsent',ref('ExternalEnrollment'),ref('DoctorConsent'))
    add(c+'/enrollments/{id}/unregister','post','certificateUnregister',ref('ExternalEnrollment'),obj({'reason':S}))
    add(c+'/dev/mock-sign','post','mockCertificateProof',ref('CertificateSession'),obj({'challengeId':U,'fingerprintSha256':{'type':['string','null']}},['challengeId']),'public',201,'Dev flag only; never real identity')
    add(c+'/mobile/transactions','post','mobileStart',body=obj({'purpose':{'enum':['CERTIFICATE_ENROLLMENT','CERTIFICATE_LOGIN']},'invitationCode':S},['purpose']),auth='public',status=201,description='503 until contracted provider adapter configured')
    add(c+'/mobile/transactions/{transactionId}/callback','post','mobileCallback',body={'type':'object'},auth='public',description='Provider adapter verifies raw callback. Unsigned SUCCESS never trusted. 503 without adapter.')
    add(c+'/mobile/transactions/{transactionId}/consume','post','mobileConsume',ref('CertificateSession'),obj({'transactionSecret':S}),'public',description='One-use secret and server-verified transaction required')
    spec['info'].update(version='0.2.0-dev',description='Development only: certificate enrollment, optional Google, OpenBao private X509 and raw ECDSA. In-memory and synthetic staff headers. No clinical validity, HSM, TSA, CAdES or contracted mobile adapter.')
    spec['x-public-operations']=sorted(PUBLIC_PATHS)

