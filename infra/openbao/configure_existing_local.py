import json, re, urllib.request, urllib.error, pathlib, datetime, sys
root=pathlib.Path(__file__).resolve().parents[2]; runtime=root/'work/openbao'
token=re.search(r'Root Token:\s*(\S+)', (runtime/'server.stdout.log').read_text()).group(1)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,data=None,method=None):
 req=urllib.request.Request('http://127.0.0.1:18200/v1/'+path,data=None if data is None else json.dumps(data).encode(),headers={'X-Vault-Token':token,'Content-Type':'application/json'},method=method)
 with opener.open(req,timeout=10) as resp:
  raw=resp.read(); return json.loads(raw) if raw else None
mounts=call('sys/mounts')['data']
for mount,kind in [('transit/','transit'),('pki/','pki')]:
 if mount not in mounts: call('sys/mounts/'+mount.rstrip('/'), {'type':kind,'config':{'max_lease_ttl':'87600h'}})
try:
 ca=call('pki/cert/ca')
except urllib.error.HTTPError as e:
 if e.code != 404: raise
 ca=call('pki/root/generate/internal',{'common_name':'INVIZ LOCAL DEVELOPMENT CA - NOT CLINICAL','ttl':'87600h','key_type':'ec','key_bits':256})
(runtime/'ca.pem').write_text(ca['data']['certificate'])
role={'allow_any_name':False,'allowed_domains':['vc-doctor-*'],'allow_glob_domains':True,'allow_bare_domains':True,'enforce_hostnames':False,'key_type':'ec','key_bits':256,'key_usage':['DigitalSignature','ContentCommitment'],'ext_key_usage':[],'server_flag':False,'client_flag':False,'no_store':False,'max_ttl':'8760h'}
call('pki/roles/vision-certi-doctor',role); print('PKI role configured',flush=True)
for name,file in [('vision-certi-provisioner','provisioner.hcl'),('vision-certi-signer','signer.hcl')]:
 call('sys/policies/acl/'+name,{'policy':(root/'infra/openbao'/file).read_text()},'PUT')
print('Separate service policies configured',flush=True)
provision=call('auth/token/create',{'policies':['vision-certi-provisioner'],'no_default_policy':True,'ttl':'24h','renewable':False})['auth']['client_token']
signer=call('auth/token/create',{'policies':['vision-certi-signer'],'no_default_policy':True,'ttl':'24h','renewable':False})['auth']['client_token']
config={'pid':int((runtime/'server.pid').read_text()),'address':'http://127.0.0.1:18200','rootToken':token,'provisioningToken':provision,'signingToken':signer,'caFile':str(runtime/'ca.pem'),'developmentOnly':True,'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(runtime/'runtime.secrets.json').write_text(json.dumps(config,indent=2))
print('OpenBao configured; credentials saved locally, not printed')