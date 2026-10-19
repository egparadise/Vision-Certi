"""Build the explicit development-only API contract. Run with Python 3.11+."""
import json
from pathlib import Path

OUT = Path(__file__).with_name('openapi.dev.json')
S = {'type': 'string'}
U = {'type': 'string', 'format': 'uuid'}
T = {'type': 'string', 'format': 'date-time'}
N = {'type': 'integer', 'minimum': 0}
B = {'type': 'boolean'}
H = {'type': 'string', 'pattern': '^[0-9a-f]{64}$'}

def ref(name):
    return {'$ref': '#/components/schemas/' + name}

def nullable(schema):
    return {'anyOf': [schema, {'type': 'null'}]}

def obj(props, optional=()):
    return {'type': 'object', 'properties': props, 'required': [k for k in props if k not in optional]}

def array(schema):
    return {'type': 'array', 'items': schema}

def listed(name):
    return obj({'items': array(ref(name)), 'nextCursor': nullable(S)})

states = ['PENDING_APPROVAL','APPROVED','SIGNING','EVIDENCE_PENDING','COMPLETED','EXPIRED','REJECTED','FAILED','RECONCILIATION_REQUIRED']
schemas = {
    'Error': obj({'code': S, 'message': S, 'correlationId': S, 'retryable': B}),
    'DocumentMetadata': obj({k: S for k in ['externalDocumentId','externalStudyId','title','signerId']}),
    'DocumentVersion': obj({'id': U, 'documentId': U, 'versionNumber': {'type':'integer','minimum':1},
        'sha256': H, 'sizeBytes': N, 'contentType': {'const':'application/pdf'}, 'createdAt': T,
        'createdBy': S, 'previousVersionId': nullable(U), 'amendmentReason': nullable(S)}),
    'Document': obj({'id': U, 'tenantId': S, 'externalDocumentId': S, 'externalStudyId': S, 'title': S,
        'signerId': S, 'createdAt': T, 'updatedAt': T, 'createdBy': S,
        'latestVersion': ref('DocumentVersion'), 'versions': array(ref('DocumentVersion'))}),
    'CreateSignRequest': obj({'documentVersionId': U, 'signerId': S}),
    'ApproveSignRequest': obj({'documentSha256': H, 'nonce': S,
        'reviewAttestation': S, 'certificateDerBase64': S,
        'externalSignatureBase64': S, 'externalSignatureAlgorithm': S},
        optional=('reviewAttestation','certificateDerBase64','externalSignatureBase64','externalSignatureAlgorithm')),
    'SignRequest': obj({'id': U, 'tenantId': S, 'documentId': U, 'documentVersionId': U,
        'documentSha256': H, 'signerId': S, 'nonce': nullable(S), 'status': {'type':'string','enum':states},
        'signatureMode': {'const':'MOCK_NOT_VALID_FOR_CLINICAL_USE'}, 'createdAt': T, 'createdBy': S,
        'expiresAt': T, 'approvedAt': nullable(T), 'approvedBy': nullable(S), 'signatureId': nullable(U)}),
    'Signature': obj({'id': U, 'tenantId': S, 'signRequestId': U, 'documentId': U, 'documentVersionId': U,
        'documentSha256': H, 'signerId': S, 'signatureMode': {'const':'MOCK_NOT_VALID_FOR_CLINICAL_USE'},
        'algorithm': S, 'signatureValue': S, 'clinicalUseAllowed': {'const':False}, 'createdAt': T}),
    'Verification': obj({'signatureId': U, 'status': {'enum':['INVALID','INDETERMINATE']},
        'signatureMode': {'const':'MOCK_NOT_VALID_FOR_CLINICAL_USE'}, 'clinicalUseAllowed': {'const':False},
        'mockSignatureIntact': B, 'checks': obj({k:{'enum':['VALID','INVALID','INDETERMINATE']} for k in
        ['documentIntegrity','signature','trustChain','timestamp','revocationAtSigning','authorizationAtSigning']}),
        'reasonCodes': array(S), 'verifiedAt': T}),
    'Delivery': obj({'id': U, 'tenantId': S, 'signatureId': U, 'signRequestId': U, 'documentId': U,
        'connectorId': S, 'eventType': S, 'destination': S,
        'status': {'enum':['QUEUED','SENDING','ACKED','RETRY_WAIT','DEAD_LETTER']},
        'attempts': N, 'createdAt': T, 'updatedAt': T}),
    'AuditEvent': obj({'id': U, 'tenantId': S, 'sequence': N, 'occurredAt': T, 'actorId': S,
        'action': S, 'targetType': S, 'targetId': S, 'outcome': S, 'correlationId': S,
        'details': {'type':'object','additionalProperties':S}, 'previousHash': S, 'hash': H}),
    'Dashboard': obj({'tenantId': S, 'generatedAt': T, 'signatureMode': S, 'persistence': {'const':'IN_MEMORY'},
        'documents': N, 'documentVersions': N, 'signRequestsByStatus': {'type':'object','additionalProperties':N},
        'signatures': N, 'deliveriesByStatus': {'type':'object','additionalProperties':N}, 'auditEvents': N}),
    'Health': obj({'status': S, 'service': S, 'activeProfiles': array(S), 'persistence': {'const':'IN_MEMORY'},
        'signatureMode': S, 'devSyntheticIdentities': B, 'clinicalUseAllowed': {'const':False}}),
}
parameters = {
    'IdempotencyKey': {'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','pattern':'^[A-Za-z0-9._:-]{8,128}$'},
        'description':'Reuse the same key for retries of one operation. Different payload returns 409.'},
}
security = [{'DevTenant': [], 'DevActor': []}]
paths = {}

def operation(path, method, name, response, status=200, request=None, multipart=False, anonymous=False):
    params = []
    for part in path.split('/'):
        if part.startswith('{'):
            params.append({'name':part[1:-1],'in':'path','required':True,'schema':U})
    if method == 'post' and not anonymous and not path.endswith('/verify'):
        params.append({'$ref':'#/components/parameters/IdempotencyKey'})
    op = {'operationId':name,'security':[] if anonymous else security,'parameters':params,
        'responses':{str(status):{'description':'Development response','content':{'application/json':{'schema':response}}},
                     'default':{'description':'Request failed','content':{'application/json':{'schema':ref('Error')}}}}}
    if request:
        media = 'multipart/form-data' if multipart else 'application/json'
        op['requestBody'] = {'required':True,'content':{media:{'schema':request}}}
    paths.setdefault(path,{})[method] = op

operation('/v1/health','get','health',ref('Health'),anonymous=True)
operation('/v1/dashboard','get','dashboard',ref('Dashboard'))
operation('/v1/documents','get','listDocuments',listed('Document'))
operation('/v1/documents','post','registerDocument',ref('Document'),201,
    obj({'metadata':{'type':'string','description':'JSON encoded DocumentMetadata'},
         'file':{'type':'string','format':'binary'}}),True)
paths['/v1/documents']['post']['responses']['200'] = {'description':'Identical registration already exists',
    'content':{'application/json':{'schema':ref('Document')}}}
operation('/v1/documents/{id}','get','getDocument',ref('Document'))
operation('/v1/documents/{id}/versions','post','amendDocument',ref('Document'),201,
    obj({'file':{'type':'string','format':'binary'},'previousVersionId':U,'amendmentReason':S}),True)
content_path='/v1/documents/{id}/versions/{versionId}/content'
operation(content_path,'get','documentContent',S)
paths[content_path]['get']['responses']['200']={'description':'Exact stored PDF bytes; no-store',
    'content':{'application/pdf':{'schema':{'type':'string','format':'binary'}}}}
operation('/v1/sign-requests','get','listSignRequests',listed('SignRequest'))
operation('/v1/sign-requests','post','createSignRequest',ref('SignRequest'),201,ref('CreateSignRequest'))
operation('/v1/sign-requests/{id}','get','getSignRequest',ref('SignRequest'))
operation('/v1/sign-requests/{id}/approve','post','approveSignRequest',ref('SignRequest'),202,ref('ApproveSignRequest'))
operation('/v1/signatures','get','listSignatures',listed('Signature'))
operation('/v1/signatures/{id}/verify','post','verifySignature',ref('Verification'))
operation('/v1/deliveries','get','listDeliveries',listed('Delivery'))
operation('/v1/deliveries/{id}','get','getDelivery',ref('Delivery'))
operation('/v1/audit-events','get','listAuditEvents',listed('AuditEvent'))
from enrollment_contract import extend
extend(schemas, operation)
spec={'openapi':'3.1.0','info':{'title':'Vision-Certi explicit development API','version':'0.1.0',
    'description':'In-memory synthetic identities and mock signatures only. NOT FOR CLINICAL USE. No production authentication, HSM, TSA, CA, WORM or hospital transmission.'},
    'servers':[{'url':'http://localhost:8080','description':'Local dev profile only'}],
    'paths':paths,'components':{'schemas':schemas,'parameters':parameters,'securitySchemes':{
        'DevTenant':{'type':'apiKey','in':'header','name':'X-Tenant-Id','description':'DEV ONLY demo-hospital-a or demo-hospital-b; not authentication'},
        'DevActor':{'type':'apiKey','in':'header','name':'X-Actor-Id','description':'DEV ONLY doctor-demo or admin-demo; not authentication'}}}}
from doctor_contract import extend as extend_doctor
extend_doctor(spec)
if __name__ == '__main__':
    OUT.write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Wrote {len(paths)} paths to {OUT}')
