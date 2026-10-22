// Browser layout/interaction test. All API responses are isolated fixtures; no production login or data write.
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(process.env.VISION_CERTI_RELEASE_DIR || 'apps/web/dist');
const base = 'https://vision-certi.web.app';
const fixture = {
  totals: { requested: 3, attempted: 2, verified: 1, redeemed: 1, checked: 1, denied: 1, loginAttempted: 2, loginSucceeded: 1, loginFailed: 1, approvalRequests: 3, approvalDocuments: 2 },
  certificates: [{ fingerprintSha256: 'a'.repeat(64), subjectDn: 'CN=화면 검증용 의사 (시험 자료)', issuerDn: 'CN=화면 검증용 CA', notAfter: '2027-10-22T00:00:00Z', verified: 1, redeemed: 1, checked: 1, lastSeenAt: '2026-10-22T06:00:00Z', attempted: 2, loginAttempted: 2, loginSucceeded: 1, loginFailed: 1, approvalRequests: 3, approvalDocuments: 2, accounts: ['reader-test'], computers: ['READING-TEST-PC'] }],
  events: [{ id: 'fixture-event', occurredAt: '2026-10-22T06:00:00Z', action: 'REDEEMED', outcome: 'SUCCESS', correlationId: 'fixture-trace', targetId: 'fixture-proof', legacy: false,
    details: { clientId: 'vision-certi-pacs', subjectDn: 'CN=화면 검증용 의사 (시험 자료)', issuerDn: 'CN=화면 검증용 CA', fingerprintSha256: 'a'.repeat(64), peerIp: '10.0.0.1', forwardedFor: '203.0.113.8', userAgent: 'PACS browser test', certificateNotAfter: '2027-10-22T00:00:00Z' } }],
  total: 1, offset: 0, limit: 50,
};
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== base) return route.abort();
      if (url.pathname.startsWith('/v1/')) {
        let body = { items: [] };
        if (url.pathname === '/v1/health') body = { status: 'UP', service: 'vision-certi-backend' };
        if (url.pathname === '/v1/dashboard') body = { tenantId: 'inviz', documents: 2, documentVersions: 3, signatures: 1, deliveriesByStatus: { QUEUED: 2 }, signRequestsByStatus: {}, auditEvents: 4 };
        if (url.pathname === '/v1/runtime/capabilities') body = { profile: 'dev', clinicalUseAllowed: false,
          persistence: { mode: 'POSTGRESQL' }, staffAuth: { mode: 'DEV_HEADERS' }, signing: { backend: 'DEV_SIMULATED', available: true },
          doctorAuth: { pcCertificate: { configured: true }, mobile: { configured: false } }, warnings: [] };
        if (url.pathname === '/v1/dev-admin/sessions') body = { token: 'fixture-only-not-a-token' };
        if (url.pathname === '/v1/admin/pacs-certificate-activity') body = fixture;
        if (['/v1/admin/medical-institutions', '/v1/admin/certificate-operations'].includes(url.pathname)) body = [];
        return route.fulfill({ json: body });
      }
      const relative = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      const file = path.resolve(root, relative);
      assert.ok(file.startsWith(root + path.sep));
      try {
        const body = await fs.readFile(file);
        const contentType = file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.html') ? 'text/html' : 'image/svg+xml';
        return route.fulfill({ body, contentType });
      } catch { return route.fulfill({ status: 404, body: '' }); }
    });
    await page.goto(base + '/#/admin/login');
    await page.getByLabel('ID', { exact: true }).fill('fixture-admin');
    await page.getByLabel('비밀번호', { exact: true }).fill('fixture-password');
    await page.getByRole('button', { name: '관리자 페이지 입장' }).click();
    const panel = page.getByTestId('pacs-certificate-activity');
    await panel.getByRole('cell').filter({ hasText: 'vision-certi-pacs' }).first().waitFor();
    await panel.getByText('상세 보기', { exact: true }).click();
    await panel.getByText('fixture-trace', { exact: true }).waitFor();
    assert.deepEqual(errors, []);
    await fs.mkdir('docs/obsidian/assets', { recursive: true });
    await page.getByRole('button', { name: '운영 대시보드', exact: true }).click();
    await page.getByText('인증서버 ON', { exact: true }).waitFor();
    // Native buttons support keyboard navigation; i buttons must not navigate.
    await page.getByRole('button', { name: '등록 판독문 도움말 보기', exact: true }).click();
    await page.getByRole('dialog', { name: '등록 판독문', exact: true }).waitFor();
    await page.getByRole('button', { name: '안내 닫기', exact: true }).click();
    const cards = [['등록 판독문 2건 · 목록으로 이동', '판독문 목록 및 등록'], ['전자서명 완료 1건 · 서명 내역으로 이동', '서명 내역 및 검증 포털'], ['전송 대기 2건 · 전송 큐로 이동', '병원 전송 큐 (Outbox)'], ['감사 이벤트 4건 · 감사 로그로 이동', '감사 이벤트 (Audit Trail)']];
    for (const [label, tab] of cards) {
      const button = page.getByRole('button', { name: label, exact: true });
      await button.focus(); await page.keyboard.press('Enter');
      assert.equal(await page.getByRole('button', { name: tab, exact: true }).getAttribute('aria-current'), 'page');
      await page.getByRole('button', { name: '운영 대시보드', exact: true }).click();
    }
    await page.getByRole('cell').filter({ hasText: 'reader-test' }).first().waitFor();
    await page.screenshot({ path: 'docs/obsidian/assets/2026-10-22_server_status_login_report_dashboard.png', fullPage: true });
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ dashboardRendered: true, detailedEvidenceVisible: true, onlineVisible: true, fourCardKeyboardNavigation: true, pageErrors: errors }));
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
