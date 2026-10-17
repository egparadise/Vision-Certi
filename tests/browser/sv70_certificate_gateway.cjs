/** Isolated behavior tests for the actual SV70 React gateway (not a full SV70 build or provider integration).
 * Uses the installed apps/web React/jsdom/TypeScript dev dependencies; all service calls are test doubles.
 * Run: node tests/browser/sv70_certificate_gateway.cjs
 * Optional: --source <path to an actual CertificateEnrollment.tsx before exporting the reviewed overlay>
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../..');
const deps = path.join(root, 'apps/web/node_modules');
const sourceFlag = process.argv.indexOf('--source');
const sourcePath = sourceFlag < 0
  ? path.join(root, 'integrations/sv70/client/src/pages/user/CertificateEnrollment.tsx')
  : path.resolve(process.argv[sourceFlag + 1]);
const { JSDOM } = require(path.join(deps, 'jsdom'));
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://pacs.test' });
global.window = dom.window;
global.document = dom.window.document;
Object.defineProperty(global, 'navigator', { configurable: true, value: dom.window.navigator });
global.HTMLElement = dom.window.HTMLElement;
global.IS_REACT_ACT_ENVIRONMENT = true;

const React = require(path.join(deps, 'react'));
const { render, screen, waitFor, fireEvent, cleanup, act } = require(path.join(deps, '@testing-library/react'));
const ts = require(path.join(deps, 'typescript'));
const compiled = ts.transpileModule(fs.readFileSync(sourcePath, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
  reportDiagnostics: true,
});
assert.equal(compiled.diagnostics.length, 0, 'Gateway TSX must transpile');

let store, config, enrollment, calls, getSession, popup, enroll, exchange;
const result = () => ({
  token: 'vc1.test-only-verified', user_data: { user_idx: 7, user_id: 'doctor7' }, vc: {},
  expiresAt: new Date(Date.now() + 60000).toISOString(),
});
const service = {
  getConfig: async () => config,
  getEnrollmentStatus: async () => ({ registrationStatus: enrollment }),
  getSession: () => { calls.session++; return getSession(); },
  certificateStepUp: async code => { calls.step++; assert.equal(code, 'test-handoff'); return exchange(); },
  startEnrollment: async () => ({ doctorId: 'test-doctor', challenge: {} }),
};
const navigate = (...args) => calls.nav.push(args);
const userStore = select => select(store);
userStore.getState = () => store;
const moduleObject = { exports: {} };
const aliases = {
  react: React,
  'react/jsx-runtime': require(path.join(deps, 'react/jsx-runtime')),
  'react-router-dom': { useNavigate: () => navigate },
  '@stores/useUserStore': { useUserStore: userStore },
  '@services/api/VisionCertiService': { VisionCertiService: service },
  '@services/api/pacsCertificateEnrollment': { enrollPacsCertificate: (...args) => enroll(...args) },
  '@services/api/certificateHandoff': { requestCertificateHandoff: () => popup() },
};
vm.runInNewContext(compiled.outputText, {
  exports: moduleObject.exports, module: moduleObject,
  require: name => { if (!(name in aliases)) throw Error(`Unexpected dependency: ${name}`); return aliases[name]; },
  console, Date, Number,
});
const Component = moduleObject.exports.default;

function setup(token = 'test-legacy-token', status = 'ACTIVE') {
  cleanup();
  calls = { nav: [], session: 0, step: 0, login: 0 };
  config = { state: 'ENABLED', enabled: true, visionCertiBaseUrl: 'https://cert.test', audience: 'pacs-sv70', tenantId: 'test-tenant' };
  enrollment = status;
  store = { token, userData: { user_idx: 7 }, actions: {
    loginWithCertificate: async data => { calls.login++; store = { ...store, token: data.token, userData: data.user_data }; },
    logout: () => { store = { ...store, token: null, userData: null }; },
  } };
  getSession = async () => ({ userIdx: 7, expiresAt: new Date(Date.now() + 60000).toISOString() });
  popup = async () => 'test-handoff';
  exchange = async () => result();
  enroll = async () => { enrollment = 'ACTIVE'; };
  render(React.createElement(Component));
}
const activeButton = () => screen.findByRole('button', { name: '공동인증서 로그인' });

(async () => {
  // ACTIVE registration cannot send a password-only user home. The explicit certificate exchange can.
  setup();
  let button = await activeButton();
  assert.equal(screen.getByRole('heading', { name: '등록된 공동인증서 로그인' }).textContent, '등록된 공동인증서 로그인');
  assert.equal(calls.nav.length, 0);
  await act(async () => fireEvent.click(button));
  assert.equal(calls.step, 1);
  assert.equal(calls.login, 1);
  assert.equal(calls.nav[0][0], '/');

  // Changing account while the popup is open must not redeem that certificate for the new account.
  setup();
  button = await activeButton();
  let resolvePopup;
  popup = () => new Promise(resolve => { resolvePopup = resolve; });
  await act(async () => fireEvent.click(button));
  store = { ...store, token: 'another-token', userData: { user_idx: 8 } };
  await act(async () => resolvePopup('test-handoff'));
  assert.equal(calls.step, 0);
  assert.equal(calls.login, 0);
  assert.equal(calls.nav.length, 0);

  // An inconsistent exchange response cannot overwrite the current account.
  setup();
  button = await activeButton();
  exchange = async () => ({ ...result(), user_data: { user_idx: 8 } });
  await act(async () => fireEvent.click(button));
  assert.equal(calls.login, 0);
  assert.equal(calls.nav.length, 0);
  assert.ok(screen.getByRole('alert'));

  // Account changes while the API exchange is in flight are checked again before storing the session.
  setup();
  button = await activeButton();
  let resolveExchange;
  exchange = () => new Promise(resolve => { resolveExchange = resolve; });
  await act(async () => fireEvent.click(button));
  store = { ...store, token: 'other-token', userData: { user_idx: 8 } };
  await act(async () => resolveExchange(result()));
  assert.equal(calls.login, 0);
  assert.equal(calls.nav.length, 0);

  // Previously stored vc1 sessions are rechecked server-side before navigation.
  setup('vc1.test-existing');
  await waitFor(() => assert.equal(calls.nav.length, 1));
  assert.equal(calls.session, 1);

  setup('vc1.test-expired');
  getSession = async () => { throw Error('expired'); };
  await screen.findByRole('alert');
  assert.equal(calls.nav.length, 0);

  // Completing enrollment still requires certificate login.
  setup('test-legacy-token', 'NOT_REGISTERED');
  await screen.findByRole('heading', { name: '최초 공동인증서 등록' });
  assert.equal((await screen.findByRole('button', { name: '공동인증서 최초 등록' })).disabled, false);
  assert.equal(screen.queryByRole('button', { name: '공동인증서 로그인' }), null);

  setup('test-legacy-token', 'PENDING');
  button = await screen.findByRole('button', { name: '공동인증서 최초 등록' });
  await waitFor(() => assert.equal(button.disabled, false));
  await act(async () => fireEvent.click(button));
  await activeButton();
  assert.equal(calls.nav.length, 0);

  setup('test-legacy-token', 'SUSPENDED');
  await screen.findByRole('alert');
  assert.equal(screen.queryByRole('button', { name: '공동인증서 로그인' }), null);
  assert.equal(screen.getByRole('button', { name: '공동인증서 최초 등록' }).disabled, true);

  // Non-physician staff retain their original login flow.
  setup('test-legacy-token', 'NOT_REQUIRED');
  await waitFor(() => assert.equal(calls.nav.length, 1));
  cleanup();
  console.log('PASS: TSX transpile and 8 isolated actual SV70 React gateway policy scenarios');
  dom.window.close();
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
  cleanup();
  dom.window.close();
});
