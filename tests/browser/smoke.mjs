import { chromium } from 'playwright';
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { extname, resolve, sep } from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const checks = [];
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
let productionServer;
page.on('pageerror', error => errors.push(error.message));
page.setDefaultTimeout(15000);
try {
  const title = `Browser synthetic ${Date.now()}`;
  const form = new FormData();
  form.append('metadata', new Blob([JSON.stringify({ externalDocumentId: title, externalStudyId: 'synthetic-browser', title, signerId: 'doctor-demo' })], { type: 'application/json' }));
  form.append('file', new Blob([await readFile(root + 'work/synthetic-test.pdf')], { type: 'application/pdf' }), 'synthetic.pdf');
  const upload = await fetch('http://127.0.0.1:8080/v1/documents', { method: 'POST', headers: { 'X-Tenant-Id': 'demo-hospital-a', 'X-Actor-Id': 'admin-demo', 'Idempotency-Key': `browser-${Date.now()}` }, body: form });
  assert.equal(upload.status, 201);
  await page.goto('http://127.0.0.1:5183');
  await page.getByRole('button', { name: '판독문 목록 및 등록' }).click();
  const row = page.getByRole('row').filter({ hasText: title });
  await row.getByRole('button', { name: '상세 및 서명' }).click();
  await page.getByRole('button', { name: '서명 요청 발급', exact: true }).click();
  const approve = page.getByTestId('doctor-sign-approve-btn');
  await approve.waitFor();
  assert.equal(await approve.isDisabled(), true);
  checks.push('Unconfirmed PDF cannot be approved');
  await page.getByTestId('pdf-view-confirm-checkbox').check();
  await page.waitForFunction(() => !document.querySelector('[data-testid="doctor-sign-approve-btn"]')?.disabled);
  await page.screenshot({ path: root + 'work/ui-desktop.png', fullPage: true });
  await page.getByLabel('사용자 역할 선택').selectOption('admin-demo');
  await page.getByTestId('admin-sign-restriction-alert').waitFor();
  assert.equal(await page.getByTestId('doctor-sign-approve-btn').count(), 0);
  checks.push('Admin cannot approve');
  await page.getByLabel('사용자 역할 선택').selectOption('doctor-demo');
  await page.getByTestId('pdf-view-confirm-checkbox').check();
  await page.getByTestId('doctor-sign-approve-btn').click();
  await page.getByText('전자서명 완료 (MOCK COMPLETED)', { exact: true }).waitFor();
  checks.push('Doctor approval completed against live backend');
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.screenshot({ path: root + 'work/ui-tablet.png', fullPage: true });
  const size = await page.evaluate(() => ({ width: window.innerWidth, content: document.documentElement.scrollWidth }));
  assert.ok(size.content <= size.width + 1, JSON.stringify(size));
  checks.push('Tablet has no page overflow');
  await page.setViewportSize({ width: 390, height: 844 });
  const mobileSize = await page.evaluate(() => ({ width: innerWidth, content: document.documentElement.scrollWidth }));
  assert.ok(mobileSize.content <= mobileSize.width + 1, JSON.stringify(mobileSize));
  await page.screenshot({ path: root + 'work/ui-mobile.png', fullPage: true });
  checks.push('Mobile has no page overflow');
  await page.getByLabel('의료기관 선택').selectOption('demo-hospital-b');
  await page.getByText('등록된 판독문이 없습니다', { exact: true }).waitFor();
  assert.equal(await page.getByText(title, { exact: true }).count(), 0);
  checks.push('Tenant switch removes previous document');
  const dist = resolve(root, 'apps/web/dist');
  productionServer = createServer(async (req, res) => {
    const path = resolve(dist, '.' + new URL(req.url, 'http://localhost').pathname);
    if (path !== dist && !path.startsWith(dist + sep)) { res.writeHead(403).end(); return; }
    try {
      const file = path === dist ? resolve(dist, 'index.html') : path;
      res.setHeader('Content-Type', { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' }[extname(file)] || 'application/octet-stream');
      res.end(await readFile(file));
    } catch { res.writeHead(404).end(); }
  });
  await new Promise(done => productionServer.listen(0, '127.0.0.1', done));
  const apiRequests = [];
  page.on('request', req => { if (new URL(req.url()).pathname.startsWith('/v1/')) apiRequests.push(req.url()); });
  await page.goto(`http://127.0.0.1:${productionServer.address().port}`);
  await page.getByTestId('production-disabled-screen').waitFor();
  assert.deepEqual(apiRequests, []);
  checks.push('Real production bundle disables demo UI and makes no API calls');
  assert.deepEqual(errors, []);
  checks.push('No browser runtime exceptions');
  await writeFile(root + 'work/browser-test-result.json', JSON.stringify({ result: 'PASS', checks }, null, 2));
  console.log(JSON.stringify({ result: 'PASS', checks }, null, 2));
} catch (error) {
  console.error(error);
  await page.screenshot({ path: root + 'work/ui-failure.png', fullPage: true });
  console.error((await page.locator('body').innerText()).slice(-5000));
  throw error;
} finally {
  productionServer?.close();
  await browser.close();
}
