import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';

// Isolated local test CA only; this browser context does not alter system/browser trust settings.
const base = 'https://192.168.45.160:5184';
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
let doctorId;
try {
  const capabilities = await (await context.request.get(`${base}/v1/runtime/capabilities`)).json();
  assert.equal(capabilities.profile, 'dev');
  assert.equal(capabilities.persistence.schemaVersion, 7);
  await mkdir('../../work/physician-account-browser', { recursive: true });
  await page.goto(`${base}/#/physician/register`);
  await page.getByRole('heading', { name: '원격판독의사 가입', exact: true }).waitFor();
  await page.screenshot({ path: '../../work/physician-account-browser/signup-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.screenshot({ path: '../../work/physician-account-browser/signup-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });
  const username = `browser${randomBytes(7).toString('hex')}`;
  const password = randomBytes(24).toString('base64url');
  await page.getByLabel(/^ID /).fill(username);
  await page.getByLabel(/^비밀번호 \(/).fill(password);
  await page.getByLabel('비밀번호 확인', { exact: true }).fill(password);
  await page.getByLabel('이름', { exact: true }).fill('Synthetic Browser Doctor');
  await page.getByLabel(/^연락처/).fill('01000000000');
  await page.getByLabel('의사면허번호', { exact: true }).fill(String(Date.now()));
  await page.getByLabel('소속 기관', { exact: true }).fill('Synthetic Browser Center');
  await page.getByRole('checkbox').check();
  const registration = page.waitForResponse(r => r.url().endsWith('/physician-accounts/registrations') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '가입하고 인증서 등록 준비' }).click();
  const response = await registration;
  assert.equal(response.status(), 201);
  const receipt = await response.json();
  doctorId = receipt.account.doctor.id;
  assert.equal(receipt.account.scope, 'ENROLLMENT_ONLY');
  assert.equal(receipt.account.phoneVerified, false);
  await page.getByRole('heading', { name: 'Synthetic Browser Doctor님의 계정' }).waitFor();
  assert.equal(await page.locator('input[type=password]').count(), 0);
  assert.equal(await page.evaluate(token => [...Object.values(localStorage), ...Object.values(sessionStorage), location.href].some(v => v.includes(token)), receipt.enrollmentToken), false);
  await page.getByRole('button', { name: '인증서 등록·재개 요청' }).click();
  await page.getByRole('region', { name: '인증서 서명 결과 제출' }).waitFor();
  assert.equal(await page.getByRole('button', { name: /보안프로그램.*설치|로컬 SDK/ }).count(), 0);
  await page.screenshot({ path: '../../work/physician-account-browser/certificate-proof.png', fullPage: true });
  const logout = page.waitForResponse(r => r.url().endsWith('/physician-accounts/logout'));
  await page.getByRole('button', { name: '가입 세션 종료' }).click();
  assert.equal((await logout).status(), 204);
  await page.reload();
  await page.getByRole('button', { name: '가입 이어하기', exact: true }).click();
  await page.getByLabel(/^ID /).fill(username);
  await page.getByLabel(/^비밀번호 \(/).fill(password);
  await page.getByRole('button', { name: '가입 절차 이어하기' }).click();
  await page.getByRole('heading', { name: 'Synthetic Browser Doctor님의 계정' }).waitFor();
  await page.getByRole('button', { name: '가입 세션 종료' }).click();
  assert.deepEqual(errors, []);
  console.log('PASS: real browser signup, mobile layout, SDK-free proof entry, memory-only limited session, logout and password resume');
} finally {
  if (doctorId) {
    const cleanup = await context.request.patch(`${base}/v1/admin/doctors/${doctorId}/status`, {
      headers: { 'X-Tenant-Id': 'demo-hospital-a', 'X-Actor-Id': 'admin-demo' },
      data: { status: 'REVOKED', reason: 'Synthetic browser signup test completed' },
    });
    assert.equal(cleanup.status(), 200);
  }
  await browser.close();
}
