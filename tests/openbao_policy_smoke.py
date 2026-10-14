import base64, json, pathlib, urllib.request, urllib.error
root=pathlib.Path(__file__).resolve().parents[1]
cfg=json.loads((root/'work/openbao/runtime.secrets.json').read_text()); evidence=json.loads((root/'work/doctor-openbao-smoke-result.json').read_text())
name='vc-doctor-'+evidence['doctorId']; op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
checks=[]
for token,path,body in [
 (cfg['signingToken'],'transit/keys/'+name,None),
 (cfg['signingToken'],'pki/sign/vision-certi-doctor',{}),
 (cfg['provisioningToken'],'transit/sign/'+name,{'input':base64.b64encode(bytes(32)).decode(),'prehashed':True}),
 (cfg['provisioningToken'],'transit/keys/'+name+'/config',{'exportable':False}),
 (cfg['provisioningToken'],'transit/export/signing-key/'+name,None),
]:
 req=urllib.request.Request(cfg['address']+'/v1/'+path,data=None if body is None else json.dumps(body).encode(),headers={'X-Vault-Token':token,'Content-Type':'application/json'})
 try:
  with op.open(req,timeout=10) as res: status=res.status
 except urllib.error.HTTPError as e: status=e.code
 assert status==403,(path,status)
 checks.append({'path':path,'status':status})
(root/'work/openbao-policy-result.json').write_text(json.dumps({'result':'PASS','checks':checks},indent=2))
print('PASS 5 actual OpenBao policy boundaries: signer cannot create/issue, provisioner cannot sign/export/reconfigure')