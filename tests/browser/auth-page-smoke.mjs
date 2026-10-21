import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const base = process.env.VISION_CERTI_BASE_URL || 'https://vision-certi.web.app';
const root = await fetch(`${base}/`, { cache: 'no-store' });
assert.equal(root.status, 200);
assert.match(root.headers.get('cache-control') || '', /no-store/);

const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
try {
  const scenarios = [
    { route: '#/login' },
    { route: '#/certificates/pacs-login' },
  ];
  if (process.env.STALE_AUTH_INDEX_HTML) {
    scenarios.push({ route: '#/login', html: await readFile(process.env.STALE_AUTH_INDEX_HTML, 'utf8') });
  }
  for (const { route, html } of scenarios) {
    const page = await browser.newPage();
    if (html) await page.route(`${base}/`, routeRequest => routeRequest.fulfill({
      status: 200, contentType: 'text/html; charset=utf-8', body: html,
    }));
    const errors = [];
    const failed = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('requestfailed', request => failed.push(`${request.url()} ${request.failure()?.errorText}`));
    const response = await page.goto(`${base}/${route}`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    const body = (await page.locator('body').innerText()).slice(0, 600);
    assert.equal(response?.status(), 200, `${route} HTTP status`);
    assert.ok(body.trim().length > 20, `${route} rendered an empty page`);
    assert.deepEqual(errors, [], `${route} page errors`);
    assert.deepEqual(failed, [], `${route} failed requests`);
    console.log(JSON.stringify({ route, staleHtml: Boolean(html), status: response?.status(), body, errors, failed }));
    await page.close();
  }
} finally {
  await browser.close();
}
