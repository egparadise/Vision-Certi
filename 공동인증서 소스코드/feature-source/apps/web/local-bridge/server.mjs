import http from 'node:http';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { PASSWORD_MISMATCH, discoverCertificates, signChallenge } from './certificates.mjs';

const PORT = 18187;
const argumentsList = process.argv.slice(2);
const origins = new Set();
const extraRoots = [];
for (let index = 0; index < argumentsList.length; index++) {
  if (argumentsList[index] === '--origin' && argumentsList[index + 1]) origins.add(argumentsList[++index]);
  else if (argumentsList[index] === '--root' && argumentsList[index + 1]) extraRoots.push(argumentsList[++index]);
  else throw new Error('사용법: node local-bridge/server.mjs --origin https://인증서버주소 [--root 인증서폴더]');
}
if (!origins.size || [...origins].some(origin => !/^https:\/\/[^/]+$/.test(origin) && !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(origin)))
  throw new Error('허용할 HTTPS 서버 주소를 --origin으로 지정해 주세요.');
const home = process.env.USERPROFILE || homedir();
const roots = [
  join(home, 'AppData', 'LocalLow', 'NPKI'),
  join(home, 'AppData', 'Local', 'NPKI'),
  join(home, 'AppData', 'Roaming', 'NPKI'),
  join(home, 'Documents', 'NPKI'),
  join(home, 'Documents'),
  join(home, 'Desktop'),
  join(home, 'Downloads'),
  ...extraRoots,
];
let privateLocations = new Map();
const lockFile = join(home, 'AppData', 'Local', 'Inviz', 'VisionCerti', 'certificate-lockout.json');
let attempts;
try { attempts = new Map(Object.entries(JSON.parse(readFileSync(lockFile, 'utf8')))); }
catch { attempts = new Map(); }
const attemptKey = id => createHash('sha256').update(id).digest('hex');
function saveAttempts() {
  mkdirSync(join(home, 'AppData', 'Local', 'Inviz', 'VisionCerti'), { recursive: true });
  const pending = `${lockFile}.tmp`;
  writeFileSync(pending, JSON.stringify(Object.fromEntries(attempts)), { mode: 0o600 });
  renameSync(pending, lockFile);
}
function json(response, status, data) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  response.end(JSON.stringify(data));
}
function cors(response, origin) {
  response.setHeader('Access-Control-Allow-Origin', origin);
  response.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  response.setHeader('Access-Control-Allow-Headers', 'content-type');
  response.setHeader('Access-Control-Allow-Private-Network', 'true');
  response.setHeader('Vary', 'Origin');
}
async function body(request) {
  const parts = [];
  let size = 0;
  for await (const part of request) {
    size += part.length;
    if (size > 128 * 1024) throw new Error('요청 크기가 너무 큽니다.');
    parts.push(part);
  }
  return JSON.parse(Buffer.concat(parts).toString('utf8'));
}
function rateLimited(id) {
  const now = Date.now();
  const key = attemptKey(id);
  const prior = attempts.get(key);
  if (!prior) return false;
  if (prior.lockedUntil && now < prior.lockedUntil) return true;
  if (prior.lockedUntil && now >= prior.lockedUntil) {
    attempts.delete(key); saveAttempts(); return false;
  }
  return false;
}
function recordFailure(id) {
  const key = attemptKey(id);
  const now = Date.now();
  const prior = attempts.get(key) ?? { failures: [], lockedUntil: 0 };
  const failures = [...prior.failures.filter(time => now - time < 10 * 60 * 1000), now];
  attempts.set(key, { failures, lockedUntil: failures.length >= 10 ? now + 10 * 60 * 1000 : 0 });
  saveAttempts();
}

const server = http.createServer(async (request, response) => {
  const host = request.headers.host;
  if (host !== `127.0.0.1:${PORT}` && host !== `localhost:${PORT}`) return json(response, 403, { error: '허용되지 않은 호스트입니다.' });
  const origin = request.headers.origin;
  if (!origin || !origins.has(origin)) return json(response, 403, { error: '허용되지 않은 호출 주소입니다.' });
  cors(response, origin);
  if (request.method === 'OPTIONS') { response.writeHead(204); response.end(); return; }
  try {
    if (request.method === 'GET' && request.url === '/v1/certificates') {
      const found = await discoverCertificates(roots);
      privateLocations = found.privateLocations;
      return json(response, 200, { certificates: found.records });
    }
    if (request.method === 'POST' && request.url === '/v1/sign') {
      if (!request.headers['content-type']?.startsWith('application/json')) return json(response, 415, { error: 'JSON 요청만 허용합니다.' });
      const input = await body(request);
      if (typeof input.certificateId !== 'string' || rateLimited(input.certificateId))
        return json(response, 429, { error: '인증서 서명 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.' });
      try {
        const proof = await signChallenge(privateLocations, input.certificateId, input.password, input.payloadBase64);
        attempts.delete(attemptKey(input.certificateId)); saveAttempts();
        return json(response, 200, proof);
      } catch (error) {
        if (error instanceof Error && error.message === PASSWORD_MISMATCH) recordFailure(input.certificateId);
        throw error;
      }
    }
    return json(response, 404, { error: '알 수 없는 요청입니다.' });
  } catch (error) {
    // Never log passwords or private key content.
    return json(response, 422, { error: error instanceof Error ? error.message : '인증서 처리에 실패했습니다.' });
  }
});
server.listen(PORT, '127.0.0.1', () => {
  process.stdout.write(`INVIZ local certificate bridge ready on 127.0.0.1:${PORT}\n`);
});
