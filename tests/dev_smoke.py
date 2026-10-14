"""Exercise the running development API using synthetic data, without third-party dependencies."""
import argparse
import hashlib
import json
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

def synthetic_pdf():
    stream=b'BT /F1 18 Tf 40 760 Td (SYNTHETIC TEST - NOT A MEDICAL RECORD) Tj ET'
    objects=[b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
    result=b'%PDF-1.4\n'; offsets=[0]
    for i,obj in enumerate(objects,1):
        offsets.append(len(result)); result+=f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n'
    xref=len(result)
    result+=f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    for offset in offsets[1:]: result+=f'{offset:010d} 00000 n \n'.encode()
    result+=f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    return result

def main():
    p=argparse.ArgumentParser(); p.add_argument('--base',default='http://127.0.0.1:18080'); args=p.parse_args()
    evidence=[]
    def call(method,path,body=None,actor='doctor-demo',tenant='demo-hospital-a',key=None,content_type=None,expected=200):
        headers={'X-Tenant-Id':tenant,'X-Actor-Id':actor}
        if key: headers['Idempotency-Key']=key
        if isinstance(body,dict): body=json.dumps(body).encode(); content_type='application/json'
        if content_type: headers['Content-Type']=content_type
        req=Request(args.base+path,data=body,method=method,headers=headers)
        try:
            with urlopen(req,timeout=20) as r: code=r.status; raw=r.read(); ctype=r.headers.get('Content-Type','')
        except HTTPError as r: code=r.code; raw=r.read(); ctype=r.headers.get('Content-Type','')
        result=json.loads(raw) if 'json' in ctype else raw
        assert code==expected,(method,path,code,expected,result)
        evidence.append({'method':method,'path':path,'status':code})
        return result
    health=call('GET','/v1/health'); assert health['clinicalUseAllowed'] is False
    pdf=synthetic_pdf(); run=str(uuid.uuid4()); boundary='certi-'+run
    metadata=json.dumps({'externalDocumentId':'smoke-'+run,'externalStudyId':'synthetic-study',
        'title':'Synthetic test only','signerId':'doctor-demo'})
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n{metadata}\r\n'
          f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+pdf+f'\r\n--{boundary}--\r\n'.encode()
    doc=call('POST','/v1/documents',body,actor='admin-demo',key='upload-'+run,
        content_type='multipart/form-data; boundary='+boundary,expected=201)
    version=doc['latestVersion']; assert version['sha256']==hashlib.sha256(pdf).hexdigest()
    content_path=f"/v1/documents/{doc['id']}/versions/{version['id']}/content"
    assert call('GET',content_path)==pdf
    call('GET',f"/v1/documents/{doc['id']}",tenant='demo-hospital-b',expected=404)
    req=call('POST','/v1/sign-requests',{'documentVersionId':version['id'],'signerId':'doctor-demo'},
        key='create-'+run,expected=201)
    approval={'documentSha256':req['documentSha256'],'nonce':req['nonce']}
    approve_path=f"/v1/sign-requests/{req['id']}/approve"
    error=call('POST',approve_path,approval,actor='admin-demo',key='admin-'+run,expected=403)
    assert error['code']=='SIGNING_NOT_PERMITTED_FOR_ROLE'
    done=call('POST',approve_path,approval,key='approve-'+run,expected=202)
    assert done['status']=='COMPLETED' and done['signatureMode']=='MOCK_NOT_VALID_FOR_CLINICAL_USE'
    assert call('POST',approve_path,approval,key='approve-'+run,expected=202)==done
    call('POST',approve_path,approval,key='replay-'+run,expected=409)
    verify=call('POST',f"/v1/signatures/{done['signatureId']}/verify")
    assert verify['status']=='INDETERMINATE' and verify['clinicalUseAllowed'] is False
    call('POST',f"/v1/signatures/{done['signatureId']}/verify",tenant='demo-hospital-b',expected=404)
    deliveries=call('GET','/v1/deliveries')['items']
    assert any(d['signatureId']==done['signatureId'] and d['status']=='QUEUED' for d in deliveries)
    assert call('GET','/v1/audit-events')['items']
    output=Path('work'); output.mkdir(exist_ok=True)
    (output/'synthetic-test.pdf').write_bytes(pdf)
    report={'result':'PASS','clinicalUseAllowed':False,'checks':evidence,'documentId':doc['id'],'signatureId':done['signatureId']}
    (output/'dev-smoke-result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(f'PASS: {len(evidence)} HTTP checks; exact bytes/hash, tenant isolation, admin denial, idempotency, replay, honest mock verification, queued delivery')

if __name__=='__main__': main()
