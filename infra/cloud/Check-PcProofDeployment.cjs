// Read-only production check. Does not create proofs or pollute clinical/login audit counts.
const assert = require('node:assert/strict');
const sites = ['vision-certi.web.app', 'vision-certi.firebaseapp.com',
  'hahattalk-vision-certi.web.app', 'hahattalk-vision-certi.firebaseapp.com'];
(async () => {
  const results = await Promise.all(sites.map(async host => {
    const response = await fetch(`https://${host}/v1/pacs-certificate-proofs/pc/capabilities`,
      { signal: AbortSignal.timeout(30000), cache: 'no-store' });
    assert.equal(response.status, 200, host);
    assert.match(response.headers.get('cache-control') || '', /no-store/);
    const capabilities = await response.json();
    assert.equal(capabilities.protocol, 'VC-PC-PROOF-S256-V1');
    assert.equal(capabilities.codeChallengeMethod, 'S256');
    assert.equal(capabilities.clientEvents, true);
    assert.equal(capabilities.clientEventPath, '/v1/pacs-certificate-proofs/pc/events');
    const health = await fetch(`https://${host}/v1/health`, { signal: AbortSignal.timeout(30000) });
    assert.equal(health.status, 200);
    assert.equal((await health.json()).status, 'UP');
    return { host, protocol: capabilities.protocol, clientEvents: true, health: 'UP' };
  }));
  console.log(JSON.stringify(results, null, 2));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
