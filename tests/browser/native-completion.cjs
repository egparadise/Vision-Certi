// Run only against a diagnostic PACS instance started with loopback CDP port 9227.
// All authentication/signing responses are isolated fixtures; no PACS login is performed.
// PACS_USE_BUILT_ASSETS=1 tests the native executable's bundled frontend.
// AUTH_USE_LIVE_ASSETS=1 loads deployed web assets while keeping all APIs isolated.
const { chromium } = require('playwright');
const forge = require('../../apps/web/node_modules/korea-forge');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const repo = path.resolve(__dirname, '../..');
const pacsDist = process.env.PACS_DIST || path.join(repo, 'work/pacs-completion-fix/client/dist');
const authDist = path.join(repo, 'apps/web/dist');
const site = 'https://vision-certi.web.app';
const keys = forge.pki.rsa.generateKeyPair(1024);
const cert = forge.pki.createCertificate();
cert.publicKey = keys.publicKey; cert.serialNumber = '01';
cert.validity.notBefore = new Date(Date.now() - 60000);
cert.validity.notAfter = new Date(Date.now() + 86400000);
cert.setSubject([{ name: 'commonName', value: 'UI TEST ONLY' }]); cert.setIssuer(cert.subject.attributes);
cert.sign(keys.privateKey, forge.md.sha256.create());
const certBytes = forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes();
const certBase64 = forge.util.encode64(certBytes);
const encrypted = forge.pki.encryptRsaPrivateKey(keys.privateKey, 'test-only-password');
const fingerprint = forge.md.sha256.create().update(certBytes).digest().toHex();
const payload = 'purpose=PACS_CERTIFICATE_PROOF\naudience=vision-certi-pacs\nnonce=isolated-ui-test\n';
const challenge = { challengeId: 'isolated-ui-test', purpose: 'PACS_CERTIFICATE_PROOF',
  signedPayload: payload, signedPayloadBase64: Buffer.from(payload).toString('base64'),
  expiresAt: new Date(Date.now() + 300000).toISOString() };
let verified = 0, brokerReceived = 0, passwordFailures = 0;
async function json(route, data, status = 200) {
  await route.fulfill({status, contentType: 'application/json', headers: {
    'Access-Control-Allow-Origin': route.request().headers().origin || site,
    'Access-Control-Allow-Headers': 'content-type, authorization',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Private-Network': 'true',
  }, body: status === 204 ? '' : JSON.stringify(data)});
}
async function staticFile(route, root) {
  const requested = decodeURIComponent(new URL(route.request().url()).pathname);
  const file = path.resolve(root, requested === '/' ? 'index.html' : requested.slice(1));
  if (!file.startsWith(path.resolve(root) + path.sep) || !fs.existsSync(file)) return route.fulfill({status: 404});
  const types = {'.js':'text/javascript','.html':'text/html','.css':'text/css','.png':'image/png','.svg':'image/svg+xml','.wasm':'application/wasm'};
  return route.fulfill({status:200, contentType:types[path.extname(file)] || 'application/octet-stream',body:fs.readFileSync(file)});
}
async function isolate(context) {
  await context.route('https://api70.cloudcare.life/**', route => json(route, {testOnly:true}, 503));
  await context.route('http://127.0.0.1:5185/**', async route => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'OPTIONS') return json(route, {}, 204);
    if (url.pathname === '/vision-certi/public-config') return json(route, {baseUrl:site,clientId:'vision-certi-pacs'});
    if (url.pathname === '/vision-certi/login') {
      assert.equal(route.request().postDataJSON().verificationCode, 'T'.repeat(43));
      brokerReceived++;
      // Stop at our responsibility boundary. Do not create a real PACS session.
      return json(route, {detail:'UI TEST ONLY: certificate result received; PACS login not executed'}, 422);
    }
    return json(route, {testOnly:true}, 503);
  });
  await context.route(site + '/**', async route => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === '/v1/runtime/capabilities') return json(route, {profile:'prod'});
    if (pathname === '/v1/pacs-certificate-proofs/challenges') return json(route, challenge, 201);
    if (pathname === '/v1/pacs-certificate-proofs/verify') {
      const input = route.request().postDataJSON();
      assert.equal(input.challengeId, challenge.challengeId);
      assert.equal(input.fingerprintSha256, fingerprint);
      assert.equal(input.certificate, certBase64);
      const digest = forge.md.sha256.create().update(payload, 'utf8').digest().getBytes();
      assert.ok(keys.publicKey.verify(digest, forge.util.decode64(input.signature)));
      verified++;
      return json(route, {verificationCode:'T'.repeat(43),audience:'vision-certi-pacs',expiresAt:new Date(Date.now()+60000).toISOString()});
    }
    if (pathname.startsWith('/v1/')) return json(route, {testOnly:true}, 503);
    return process.env.AUTH_USE_LIVE_ASSETS === '1' ? route.continue() : staticFile(route, authDist);
  });
  await context.route('http://127.0.0.1:18187/**', async route => {
    if (route.request().method() === 'OPTIONS') return json(route, {}, 204);
    if (new URL(route.request().url()).pathname === '/v1/certificates') return json(route, {certificates:[{
      id:'c'.repeat(64),kind:'NPKI',name:'UI TEST ONLY',issuer:'Test fixture',expiresAt:cert.validity.notAfter.toISOString(),location:'test fixture'}]});
    const input = route.request().postDataJSON();
    let key = null;
    try { key = forge.pki.decryptRsaPrivateKey(encrypted, input.password); } catch { /* Wrong passwords may produce invalid ASN.1. */ }
    if (!key) { passwordFailures++; return json(route, {error:'인증서 비밀번호가 올바르지 않습니다.'},422); }
    assert.equal(input.payloadBase64, challenge.signedPayloadBase64);
    return json(route, {certificateDerBase64:certBase64,chainDerBase64:[],fingerprintSha256:fingerprint,
      signatureAlgorithm:'SHA256withRSA',signatureBase64:forge.util.encode64(key.sign(forge.md.sha256.create().update(payload,'utf8')))});
  });
}
(async () => {
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9227');
  const context = browser.contexts()[0];
  await isolate(context);
  if (process.env.PACS_USE_BUILT_ASSETS !== '1') {
    await context.route('http://tauri.localhost/**', route => staticFile(route, pacsDist));
  }
  const parent = context.pages().find(p => p.url().startsWith('http://tauri.localhost'));
  assert.ok(parent, 'Start the diagnostic PACS on its login screen first');
  await parent.goto('http://tauri.localhost/index.html#/login', {waitUntil:'domcontentloaded'});
  await parent.reload({waitUntil:'domcontentloaded'});
  const certificateButton = parent.getByRole('button', {name:/공동인증서 로그인/});
  await certificateButton.waitFor({timeout:30000});
  await parent.locator('input:not([type="checkbox"]):not([type="radio"])').evaluateAll(inputs => inputs.forEach(input => {
    if (input.type === 'text' || input.type === 'password') {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
      setter.call(input,''); input.dispatchEvent(new Event('input',{bubbles:true}));
    }
  }));
  const existingTargets = new Set((await (await fetch('http://127.0.0.1:9227/json/list')).json()).map(target => target.id));
  await certificateButton.click();
  let popupBrowser, popup;
  for (let attempt=0;attempt<50;attempt++) {
    const targets = await (await fetch('http://127.0.0.1:9227/json/list')).json();
    const target = targets.find(target => !existingTargets.has(target.id) && target.url.startsWith(site));
    if (target) {
      popupBrowser = await chromium.connectOverCDP('http://127.0.0.1:9227');
      popup = popupBrowser.contexts()[0].pages().find(p=>p.url() === target.url);
      if (popup) break;
    }
    await new Promise(resolve=>setTimeout(resolve,300));
  }
  assert.ok(popup, 'PACS did not navigate its certificate popup');
  await isolate(popupBrowser.contexts()[0]);
  await popup.reload({waitUntil:'domcontentloaded'});
  const field = popup.getByLabel('인증서 비밀번호', {exact:true});
  await field.waitFor({timeout:30000});
  await field.fill('wrong-password');
  await popup.getByRole('button', {name:'공동인증서 확인',exact:true}).click();
  await popup.getByRole('alert').filter({hasText:'비밀번호'}).waitFor();
  assert.equal(verified,0); assert.equal(brokerReceived,0);
  await field.fill('test-only-password');
  await popup.getByRole('button', {name:'공동인증서 확인',exact:true}).click();
  await popup.getByRole('heading',{name:'인증되었습니다.',exact:true}).waitFor({timeout:15000});
  await new Promise(resolve=>setTimeout(resolve,2000));
  assert.equal(passwordFailures,1); assert.equal(verified,1); assert.equal(brokerReceived,1);
  assert.equal(popup.isClosed(),false);
  await popup.screenshot({path:path.join(repo,'work/native-certificate-complete-after.png')});
  console.log(JSON.stringify({wrongPasswordRejected:true,signatureVerified:true,pacsReceivedOnce:true,
    completionRemainsVisible:true,realPacsLoginAttempted:false}));
  await popupBrowser.close(); await browser.close();
})().catch(error=>{console.error(error.stack);process.exit(1)});
