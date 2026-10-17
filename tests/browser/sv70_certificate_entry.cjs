/** Exercise the actual PACS login form's first-enrollment and returning-doctor paths. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../..');
const deps = path.join(root, 'apps/web/node_modules');
const { JSDOM } = require(path.join(deps, 'jsdom'));
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://pacs.test' });
global.window = dom.window;
global.document = dom.window.document;
Object.defineProperty(global, 'navigator', { configurable: true, value: dom.window.navigator });
global.HTMLElement = dom.window.HTMLElement;
global.IS_REACT_ACT_ENVIRONMENT = true;
window.api = { getAppVersion: async () => ({ oldVersion: 'test' }), getUserRemember: async () => null };

const React = require(path.join(deps, 'react'));
const { render, screen, fireEvent, cleanup, act } = require(path.join(deps, '@testing-library/react'));
const ts = require(path.join(deps, 'typescript'));
const source = fs.readFileSync(path.join(root, 'integrations/sv70/client/src/components/user/LoginForm.tsx'), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
  reportDiagnostics: true,
});
assert.equal(compiled.diagnostics.length, 0);

let values, calls;
const config = { enabled: true, state: 'ENABLED', visionCertiBaseUrl: 'https://cert.test', audience: 'pacs-sv70' };
const store = { userAuthProfile: null, userData: null, token: null, actions: {
  userRemember: () => {}, loginWithCertificate: async data => { calls.certSession = data; },
} };
const aliases = {
  react: React,
  'react/jsx-runtime': require(path.join(deps, 'react/jsx-runtime')),
  '@assets/img/logo/saintview_logo.svg': { ReactComponent: () => null },
  'react-i18next': { useTranslation: () => ({ t: key => key }) },
  'react-hook-form': { useForm: () => ({
    register: name => ({ name, onChange: e => { values[name] = e.target.value; } }),
    handleSubmit: callback => e => { e.preventDefault(); callback({ ...values }); },
    getValues: name => name ? values[name] : { ...values }, reset: () => {},
    setValue: (name, value) => { values[name] = value; },
  }) },
  '@libs/encryptStorage': { encryptLocalStorage: { removeItem: () => {}, setItem: () => {} } },
  'react-router-dom': { useNavigate: () => route => { calls.navigation.push(route); } },
  '@stores/useUserStore': { useUserStore: selector => selector(store) },
  '@queries/useUsers': { useUserLogin: () => ({ mutate: data => { calls.pacsLogin.push(data); }, isPending: false }) },
  '@stores/useSettingStore': { useSettingStore: () => ({ appVersion: { oldVersion: 'test' }, actions: { setInitialConfig: () => {} } }) },
  '@libs/PerformanceLogger': { measureAsync: async (_, run) => run() },
  '@components/modal/ReleaseNotesModal': { __esModule: true, default: () => null },
  '@services/api/VisionCertiService': { VisionCertiService: {
    getConfig: async () => config,
    certificateLogin: async code => { calls.exchange.push(code); return { token: 'vc1.test', user_data: { user_idx: 7 } }; },
  } },
  '@services/api/certificateHandoff': { requestCertificateHandoff: async () => { calls.handoffs++; return 'handoff-test'; } },
};
const moduleObject = { exports: {} };
vm.runInNewContext(compiled.outputText, {
  exports: moduleObject.exports, module: moduleObject,
  require: name => { if (!(name in aliases)) throw Error(`Unexpected dependency: ${name}`); return aliases[name]; },
  console: { log: () => {}, warn: () => {}, error: () => {} }, window, Date,
});
const LoginForm = moduleObject.exports.default;

async function setup() {
  cleanup();
  values = { user_id: '', user_passwd: '', user_overwrite: true };
  calls = { pacsLogin: [], handoffs: 0, exchange: [], navigation: [], certSession: null };
  render(React.createElement(LoginForm));
  return screen.findByTestId('pacs-certificate-login-btn');
}

(async () => {
  let button = await setup();
  fireEvent.change(screen.getByPlaceholderText('login.userIdPlaceholder'), { target: { value: 'doctor7' } });
  fireEvent.change(screen.getByPlaceholderText('login.userPasswdPlaceholder'), { target: { value: 'secret' } });
  await act(async () => fireEvent.click(button));
  assert.equal(JSON.stringify(calls.pacsLogin), JSON.stringify([{ user_id: 'doctor7', user_passwd: 'secret', user_overwrite: true }]));
  assert.equal(calls.handoffs, 0, 'unregistered user must authenticate to PACS before registration');

  button = await setup();
  fireEvent.change(screen.getByPlaceholderText('login.userIdPlaceholder'), { target: { value: 'doctor7' } });
  await act(async () => fireEvent.click(button));
  assert.equal(calls.handoffs, 0);
  assert.equal(calls.pacsLogin.length, 0);
  assert.ok(screen.getByTestId('pacs-certificate-login-error').textContent.includes('ID와 비밀번호'));

  button = await setup();
  await act(async () => fireEvent.click(button));
  assert.equal(calls.handoffs, 1);
  assert.deepEqual(calls.exchange, ['handoff-test']);
  assert.equal(calls.certSession.token, 'vc1.test');
  assert.deepEqual(calls.navigation, ['/']);
  cleanup(); dom.window.close();
  console.log('PASS: PACS initial credential bootstrap, incomplete input, returning certificate login');
})().catch(e => { console.error(e); process.exitCode = 1; cleanup(); dom.window.close(); });
