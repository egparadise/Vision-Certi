import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url));
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1100}});
const admin=await context.newPage(), doctor=await context.newPage();
admin.setDefaultTimeout(25000); doctor.setDefaultTimeout(25000);
const checks=[],errors=[];
for(const page of [admin,doctor]) page.on('pageerror',e=>errors.push(e.message));
const adminHeaders={'X-Actor-Id':'admin-demo','X-Tenant-Id':'demo-hospital-a'};
async function shot(page,name){await page.screenshot({path:root+'work/certificate-'+name+'.png',fullPage:true});}
async function adminMode(){await admin.locator('#actor-select').selectOption('admin-demo');}
try{
 await admin.goto('http://127.0.0.1:5173/#/doctors'); await adminMode();
 const suffix=Date.now().toString();
 for(const [field,value] of Object.entries({name:'브라우저 검증 의사',license:suffix,center:'합성 판독센터',email:'browser'+suffix+'@example.invalid','id-evidence':'synthetic/identity','license-evidence':'synthetic/license'})){
  await admin.getByTestId('prereg-'+field+'-input').fill(value);
 }
 assert.equal(await admin.getByTestId('submit-preregister-btn').isEnabled(),false);
 await admin.getByTestId('admin-reviewed-evidence-checkbox').check();
 const createdPromise=admin.waitForResponse(r=>r.url().endsWith('/v1/admin/doctors') && r.request().method()==='POST');
 await admin.getByTestId('submit-preregister-btn').click();
 const createdResponse=await createdPromise; assert.equal(createdResponse.status(),201);
 const created=await createdResponse.json(); const id=created.doctor.id;
 assert.equal(created.doctor.status,'PENDING'); assert.equal(created.doctor.certificate,null);
 checks.push('administrator preregistration without key or certificate');
 await doctor.goto('http://127.0.0.1:5173/#/certificates/enroll');
 await doctor.getByTestId('cert-invitation-code-input').fill(created.invitationCode);
 await doctor.getByTestId('request-cert-challenge-btn').click();
 await doctor.getByTestId('canonical-bytes-preview').waitFor();
 assert.equal(await doctor.locator('input[type=password]').count(),0);
 await doctor.getByTestId('dev-mock-sign-btn').click();
 await doctor.getByTestId('cert-step-3').waitFor();
 checks.push('explicit MOCK_CERTIFICATE proof via live backend');
 assert.equal(await doctor.getByTestId('submit-consent-btn').isEnabled(),false);
 for(const name of ['auth','sig','priv']) await doctor.getByTestId('consent-checkbox-'+name).check();
 await doctor.getByTestId('submit-consent-btn').click();
 await doctor.getByTestId('admin-review-pending-banner').waitFor();
 checks.push('consent alone cannot activate signing');
 await admin.reload(); await adminMode();
 await admin.getByTestId('admin-cert-review-btn-'+id).click();
 await admin.getByTestId('admin-cert-review-evidence-input').fill('synthetic/browser-fingerprint-review');
 await admin.getByTestId('admin-cert-review-checkbox').check();
 await admin.getByTestId('admin-cert-review-approve-btn').click();
 await doctor.getByTestId('refresh-enrollment-btn').click();
 await doctor.getByTestId('activate-doctor-btn').click();
 await doctor.getByTestId('physician-signing-portal').waitFor();
 checks.push('administrator identity review followed by real OpenBao activation');
 await shot(doctor,'activated');
 const pdf=await readFile(root+'work/synthetic-test.pdf');
 let r=await context.request.post('http://127.0.0.1:8080/v1/documents',{
  headers:{...adminHeaders,'Idempotency-Key':'browser-upload-'+suffix},
  multipart:{metadata:{name:'metadata.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({externalDocumentId:'browser-'+suffix,externalStudyId:'synthetic',title:'Synthetic browser report',signerId:id}))},
  file:{name:'synthetic.pdf',mimeType:'application/pdf',buffer:pdf}}});
 assert.equal(r.status(),201,await r.text()); const doc=await r.json();
 r=await context.request.post('http://127.0.0.1:8080/v1/sign-requests',{headers:{...adminHeaders,'Idempotency-Key':'browser-sign-'+suffix},data:{documentVersionId:doc.latestVersion.id,signerId:id}});
 assert.equal(r.status(),201,await r.text());
 await doctor.getByTestId('refresh-sign-requests-btn').click();
 await doctor.getByTestId('preview-document-pdf').waitFor();
 await shot(doctor,'report-review');
 const approve=doctor.getByTestId('doctor-sign-approve-btn');
 assert.equal(await approve.isEnabled(),false);
 await doctor.getByTestId('doctor-sign-confirm-checkbox').check();
 await approve.click();
 await doctor.getByTestId('doctor-sign-success').waitFor();
 checks.push('PDF byte preview and explicit per-document approval');
 await doctor.setViewportSize({width:375,height:900});
 await shot(doctor,'mobile');
 const overflow=await doctor.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth+1);
 assert.equal(overflow,false,'Mobile horizontal page overflow');
 checks.push('375px responsive certificate/physician portal');
 await doctor.getByTestId('cert-signout-btn').click();
 await doctor.getByTestId('tab-mobile-cert').click();
 await doctor.getByTestId('mobile-unconfigured-banner').waitFor();
 checks.push('unconfigured mobile provider disclosed');
 assert.deepEqual(errors,[]);
 await shot(admin,'admin-reviewed');
 await writeFile(root+'work/certificate-browser-result.json',JSON.stringify({result:'PASS',checks,identity:'MOCK_CERTIFICATE',signing:'REAL_OPENBAO',clinicalUseAllowed:false},null,2));
 console.log(JSON.stringify({result:'PASS',checks}));
} catch(e){await shot(doctor,'failure-doctor');await shot(admin,'failure-admin');console.error(e);process.exitCode=1;}
finally{await browser.close();}
