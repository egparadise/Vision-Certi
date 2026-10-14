"""Enrollment schemas for the explicitly mocked development API."""
def extend(schemas, operation):
    text = {'type':'string'}
    time = {'type':'string','format':'date-time'}
    ref = lambda name: {'$ref':'#/components/schemas/'+name}
    def obj(props, optional=()):
        return {'type':'object','properties':props,'required':[k for k in props if k not in optional]}
    optional_text = {'type':['string','null']}
    schemas.update({
        'PhoneChallengeRequest':obj({'phone':dict(text,description='010 followed by 8 digits; spaces and hyphens accepted')}),
        'PhoneChallengeResponse':obj({'challengeId':{'type':'string','format':'uuid'},'expiresAt':time,'resendAfter':time,'mode':{'const':'MOCK_SMS'},'devCode':{'type':'string','pattern':'^[0-9]{6}$','description':'Development mock only; no SMS sent'}},('devCode',)),
        'PhoneCodeRequest':obj({'code':{'type':'string','pattern':'^[0-9]{6}$','writeOnly':True}}),
        'PhoneVerificationResponse':obj({'verificationToken':{'type':'string','pattern':'^[A-Za-z0-9_-]{43}$','description':'Phone-bound, single-use, expires after 10 minutes'},'expiresAt':time,'mode':{'const':'MOCK_SMS'}}),
        'EnrollmentConsents':obj({'authentication':{'const':True},'signature':{'const':True},'privacy':{'const':True},'version':{'const':'2026-10-13-draft'}}),
        'RegistrationRequest':obj({'accountType':{'enum':['PERSONAL','CLINICIAN']},'username':{'type':'string','pattern':'^[A-Za-z][A-Za-z0-9_.-]{3,31}$'},'password':{'type':'string','minLength':12,'maxLength':128,'writeOnly':True},'name':{'type':'string','minLength':2,'maxLength':50},'phone':text,'email':optional_text,'institution':optional_text,'licenseNumber':optional_text,'specialty':optional_text,'verificationToken':{'type':'string','writeOnly':True},'consents':ref('EnrollmentConsents')},('email','institution','licenseNumber','specialty')),
        'RegistrationResponse':obj({'registrationId':{'type':'string','format':'uuid'},'username':text,'accountType':{'enum':['PERSONAL','CLINICIAN']},'status':{'enum':['REGISTERED','PENDING_CREDENTIAL_REVIEW']},'phoneVerification':{'const':'MOCK_VERIFIED'},'registeredAt':time})
    })
    schemas['RegistrationRequest']['description']='CLINICIAN requires institution (2-100), licenseNumber (1-20 ASCII digits), specialty (2-50); PERSONAL must omit or null these. Phone proof and all consents required. No login, membership or signing privilege granted.'
    operation('/v1/enrollment/phone-challenges','post','createPhoneChallenge',ref('PhoneChallengeResponse'),201,ref('PhoneChallengeRequest'),anonymous=True)
    operation('/v1/enrollment/phone-challenges/{challengeId}/verify','post','verifyPhoneChallenge',ref('PhoneVerificationResponse'),200,ref('PhoneCodeRequest'),anonymous=True)
    operation('/v1/enrollment/registrations','post','registerAccount',ref('RegistrationResponse'),201,ref('RegistrationRequest'),anonymous=True)