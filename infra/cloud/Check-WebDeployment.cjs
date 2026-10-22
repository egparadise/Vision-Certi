// Compare both Firebase Hosting sites with the local build, including retired JS URLs.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const dist = process.env.VISION_CERTI_RELEASE_DIR
  ? path.resolve(process.env.VISION_CERTI_RELEASE_DIR) : path.resolve(__dirname, '../../apps/web/dist');
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
async function check() {
  const html = fs.readFileSync(path.join(dist, 'index.html'));
  const localEntry = html.toString().match(/src="(\/assets\/index-[^"]+\.js)"/)[1];
  const script = fs.readFileSync(path.join(dist, localEntry));
  const sites = ['vision-certi.web.app', 'vision-certi.firebaseapp.com',
    'hahattalk-vision-certi.web.app', 'hahattalk-vision-certi.firebaseapp.com'];
  const results = await Promise.all(sites.map(async host => {
    try {
      const base = 'https://' + host;
      const options = {signal: AbortSignal.timeout(30000)};
      const response = await fetch(base + '/?deployment-check=' + Date.now(), options);
      const remoteHtml = Buffer.from(await response.arrayBuffer());
      const entry = remoteHtml.toString().match(/src="(\/assets\/index-[^"]+\.js)"/)?.[1];
      if (!entry) throw new Error('Missing JavaScript entrypoint');
      const js = await fetch(base + entry, options);
      const remoteJs = Buffer.from(await js.arrayBuffer());
      const fallback = await fetch(base + '/assets/index-retired-deployment-check.js', options);
      const fallbackJs = Buffer.from(await fallback.arrayBuffer());
      const result = {host, htmlStatus:response.status, scriptStatus:js.status,
        htmlMatches:digest(html) === digest(remoteHtml), scriptMatches:digest(script) === digest(remoteJs),
        cacheDisabled:[response,js].every(r => r.headers.get('cache-control')?.includes('no-store')),
        retiredEntryWorks:fallback.status === 200 && digest(fallbackJs) === digest(script)};
      return {...result, ok:result.htmlStatus === 200 && result.scriptStatus === 200 && result.htmlMatches &&
        result.scriptMatches && result.cacheDisabled && result.retiredEntryWorks};
    } catch (error) { return {host,ok:false,error:error.message}; }
  }));
  console.log(JSON.stringify({checkedAt:new Date().toISOString(),results},null,2));
  if (results.some(result => !result.ok)) process.exitCode = 1;
}
check().catch(error => {console.error(error.message);process.exitCode = 1;});
