import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ExternalCertificateProof } from '../components/ExternalCertificateProof';
import type { CertificateChallenge } from '../types/api';
import appPackage from '../../package.json';

const mocks = vi.hoisted(() => ({
  getRememberedDirectory: vi.fn(),
  rememberDirectory: vi.fn(),
  scanCertificateDirectory: vi.fn(),
  signWithCertificateFiles: vi.fn(),
  discoverBridgeCertificates: vi.fn(),
  probeLocalCertificateBridge: vi.fn(),
}));
vi.mock('../services/localCertificateDiscovery', () => ({
  getRememberedDirectory: mocks.getRememberedDirectory,
  rememberDirectory: mocks.rememberDirectory,
  scanCertificateDirectory: mocks.scanCertificateDirectory,
}));
vi.mock('../services/certificateFileSigner', () => ({
  signWithCertificateFiles: mocks.signWithCertificateFiles,
  PASSWORD_MISMATCH: '인증서 비밀번호가 일치하지 않습니다. 선택한 인증서의 비밀번호를 다시 입력해 주세요.',
}));
vi.mock('../services/localCertificateBridge', () => ({
  discoverBridgeCertificates: mocks.discoverBridgeCertificates,
  probeLocalCertificateBridge: mocks.probeLocalCertificateBridge,
}));
beforeEach(() => { vi.clearAllMocks(); mocks.probeLocalCertificateBridge.mockResolvedValue(false); });
afterEach(() => vi.unstubAllEnvs());

it('keeps the Windows installer link usable after the certificate challenge expires', async () => {
  const installerUrl = 'https://vision-certi.web.app/downloads/vision-certi-bridge-win-x64.zip';
  vi.stubEnv('VITE_CERT_INSTALLER_URL', installerUrl);
  mocks.discoverBridgeCertificates.mockRejectedValue(new TypeError('Failed to fetch'));
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'expired-setup', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() - 1000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  const link = await screen.findByRole('link', { name: 'Windows 인증서 프로그램 다운로드' });
  expect(link).toHaveAttribute('href', installerUrl);
  expect(link).not.toHaveAttribute('target', '_blank');
  expect(link).toHaveAttribute('download', 'vision-certi-bridge-win-x64.zip');
  expect(link.closest('fieldset')).toBeNull();
  link.addEventListener('click', event => event.preventDefault(), { once: true });
  fireEvent.click(link);
  expect(screen.getByLabelText('인증서 프로그램 설치 안내')).toHaveTextContent('Install.cmd');
  expect(screen.getByText(installerUrl)).toBeInTheDocument();
});

it('offers local program setup and retry when the browser cannot reach this PC', async () => {
  mocks.discoverBridgeCertificates.mockRejectedValue(new TypeError('Failed to fetch'));
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'setup', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  expect(await screen.findByText('이 PC의 인증서 프로그램에 연결할 수 없습니다')).toBeInTheDocument();
  expect(screen.getByText(/로컬 네트워크 접근을 허용해 주세요/)).toBeInTheDocument();
  mocks.discoverBridgeCertificates.mockResolvedValue([]);
  fireEvent.click(screen.getByRole('button', { name: '설치 후 다시 검색' }));
  await waitFor(() => expect(screen.queryByText('이 PC의 인증서 프로그램에 연결할 수 없습니다')).not.toBeInTheDocument());
});

it('rescans automatically when the installed PC program becomes reachable', async () => {
  mocks.discoverBridgeCertificates.mockRejectedValueOnce(new TypeError('Failed to fetch'));
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'auto-rescan', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  await screen.findByText('이 PC의 인증서 프로그램에 연결할 수 없습니다');
  mocks.probeLocalCertificateBridge.mockResolvedValue(true);
  mocks.discoverBridgeCertificates.mockResolvedValue([{
    id: 'bridge:doctor', kind: 'NPKI', name: '의사 인증서', issuer: '인증기관',
    path: '이 PC의 NPKI', source: 'LOCAL_BRIDGE', sign: vi.fn(),
  }]);
  fireEvent(window, new Event('focus'));
  expect(await screen.findByRole('radio', { name: '의사 인증서 인증서 선택' })).toBeChecked();
  expect(screen.getByLabelText('인증서 비밀번호')).toBeEnabled();
});

it('requests a fresh challenge after installation if the previous one expired', async () => {
  mocks.discoverBridgeCertificates.mockRejectedValueOnce(new TypeError('Failed to fetch'));
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  const restart = vi.fn();
  render(<ExternalCertificateProof challenge={{ challengeId: 'expired-while-installing', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() - 1000).toISOString() } as CertificateChallenge} busy={false}
    onProof={async () => {}} onRestart={restart} />);
  await screen.findByText('이 PC의 인증서 프로그램에 연결할 수 없습니다');
  mocks.probeLocalCertificateBridge.mockResolvedValue(true);
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(restart).toHaveBeenCalledTimes(1));
});

it('offers to start a new request once the challenge has expired', async () => {
  mocks.discoverBridgeCertificates.mockResolvedValue([]);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  const restart = vi.fn();
  render(<ExternalCertificateProof challenge={{ challengeId: 'expired-visible', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() - 1000).toISOString() } as CertificateChallenge} busy={false}
    onProof={async () => {}} onRestart={restart} />);
  expect(screen.getByText(/인증 요청이 만료되었습니다/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '새 인증 요청 시작' }));
  expect(restart).toHaveBeenCalledTimes(1);
});

it('shows no restart button when the caller cannot issue a new request', async () => {
  mocks.discoverBridgeCertificates.mockResolvedValue([]);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'expired-fixed', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() - 1000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  expect(screen.getByText(/인증 요청이 만료되었습니다/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '새 인증 요청 시작' })).toBeNull();
});

it('asks the PC program to scan removable drives when that storage medium is selected', async () => {
  mocks.discoverBridgeCertificates.mockImplementation(async (medium: string) => medium === 'removable-disk' ? [{
    id: 'bridge:usb', kind: 'NPKI', name: 'USB 인증서', issuer: '발급기관',
    path: '이동식디스크의 NPKI', source: 'LOCAL_BRIDGE', sign: vi.fn(),
  }] : []);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'usb', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  fireEvent.click(screen.getByRole('button', { name: '이동식디스크' }));
  expect(await screen.findByRole('radio', { name: 'USB 인증서 인증서 선택' })).toBeInTheDocument();
  expect(mocks.discoverBridgeCertificates).toHaveBeenCalledWith('removable-disk');
});

it('shows an authorized certificate automatically and signs only after selection and password', async () => {
  mocks.discoverBridgeCertificates.mockResolvedValue([]);
  const bundle = new File(['pfx bytes'], 'doctor.p12');
  const getFiles = vi.fn(async () => ({ kind: 'PFX' as const, bundle }));
  const handle = { queryPermission: vi.fn(async () => 'granted') };
  mocks.getRememberedDirectory.mockImplementation(async (medium: string) => medium === 'hard-disk' ? handle : undefined);
  mocks.scanCertificateDirectory.mockResolvedValue([{
    id: 'hard-disk:doctor.p12:PFX', kind: 'PFX', name: 'doctor.p12', issuer: '비밀번호 입력 후 확인',
    path: 'doctor.p12', getFiles,
  }]);
  const proof = { certificateDerBase64: 'Y2VydA==', signatureBase64: 'c2lnbg==',
    chainDerBase64: [], signatureAlgorithm: 'SHA256withRSA', fingerprintSha256: 'a'.repeat(64) };
  mocks.signWithCertificateFiles.mockResolvedValue(proof);
  const onProof = vi.fn(async () => {});
  render(<ExternalCertificateProof challenge={{ challengeId: 'test', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={onProof} />);

  expect(screen.getByText('공동인증서 인증')).toBeInTheDocument();
  expect(screen.getByText(`v${appPackage.version}`)).toBeInTheDocument();
  expect(screen.getByAltText('INVIZ')).toHaveAttribute('src', '/inviz-logo-transparent.png');
  expect(screen.getByRole('button', { name: '하드디스크' })).toHaveAttribute('aria-pressed', 'true');
  const row = await screen.findByRole('radio', { name: 'doctor.p12 인증서 선택' });
  expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
  expect(getFiles).not.toHaveBeenCalled();
  fireEvent.click(row);
  fireEvent.change(screen.getByLabelText('인증서 비밀번호'), { target: { value: 'secret' } });
  fireEvent.submit(screen.getByRole('button', { name: '확인' }).closest('form')!);
  await waitFor(() => expect(onProof).toHaveBeenCalledWith(proof));
  expect(mocks.signWithCertificateFiles).toHaveBeenCalledWith({ kind: 'PFX', bundle }, 'secret', btoa('challenge'));
  expect(getFiles).toHaveBeenCalledTimes(1);
  expect(screen.getByLabelText('인증서 비밀번호')).toHaveValue('');
});

it('opens the on-screen keyboard and enters letters, digits and symbols into the certificate password', async () => {
  mocks.discoverBridgeCertificates.mockResolvedValue([]);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  mocks.signWithCertificateFiles.mockResolvedValue({
    certificateDerBase64: 'Y2VydA==', signatureBase64: 'c2lnbg==', chainDerBase64: [],
    signatureAlgorithm: 'SHA256withRSA', fingerprintSha256: 'a'.repeat(64),
  });
  render(<ExternalCertificateProof challenge={{ challengeId: 'keyboard', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  await screen.findByText('하드디스크에서 인증서를 찾지 못했습니다. 저장매체 연결과 인증서 위치를 확인해 주세요.');
  fireEvent.click(screen.getByText('목록에 없나요? 인증서 파일 직접 선택'));
  const bundle = new File(['bundle'], 'doctor.p12');
  fireEvent.change(screen.getByLabelText('PFX/P12 파일'), { target: { files: [bundle] } });
  fireEvent.click(screen.getByRole('button', { name: '가상키보드 열기' }));
  const keyboard = screen.getByRole('group', { name: '가상키보드' });
  fireEvent.click(within(keyboard).getByRole('button', { name: '1' }));
  fireEvent.click(within(keyboard).getByRole('button', { name: '2' }));
  fireEvent.click(within(keyboard).getByRole('button', { name: 'Shift' }));
  fireEvent.click(within(keyboard).getByRole('button', { name: 'A' }));
  fireEvent.click(within(keyboard).getByRole('button', { name: '기호' }));
  fireEvent.click(within(keyboard).getByRole('button', { name: '#' }));
  fireEvent.click(within(keyboard).getByRole('button', { name: '지우기' }));
  expect(screen.getByLabelText('인증서 비밀번호')).toHaveValue('12A');
  fireEvent.submit(screen.getByRole('button', { name: '확인' }).closest('form')!);
  await waitFor(() => expect(mocks.signWithCertificateFiles).toHaveBeenCalledWith(
    { kind: 'PFX', bundle }, '12A', btoa('challenge')));
});

it('lists a local NPKI certificate and signs through the PC bridge without reading its private-key file in the browser', async () => {
  const proof = { certificateDerBase64: 'Y2VydA==', signatureBase64: 'c2lnbg==',
    chainDerBase64: [], signatureAlgorithm: 'SHA256withRSA', fingerprintSha256: 'a'.repeat(64) };
  const sign = vi.fn(async () => proof);
  mocks.discoverBridgeCertificates.mockImplementation(async (medium: string) => medium === 'hard-disk' ? [{
    id: 'bridge:cert', kind: 'NPKI', name: 'PC 인증서', issuer: '인증기관',
    path: 'yessign/USER', source: 'LOCAL_BRIDGE', sign,
  }] : []);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  const onProof = vi.fn(async () => {});
  render(<ExternalCertificateProof challenge={{ challengeId: 'local', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={onProof} />);
  const row = await screen.findByRole('radio', { name: 'PC 인증서 인증서 선택' });
  expect(row).toBeChecked();
  fireEvent.change(screen.getByLabelText('인증서 비밀번호'), { target: { value: 'secret' } });
  fireEvent.submit(screen.getByRole('button', { name: '확인' }).closest('form')!);
  await waitFor(() => expect(onProof).toHaveBeenCalledWith(proof));
  expect(sign).toHaveBeenCalledWith('secret', btoa('challenge'));
  expect(mocks.signWithCertificateFiles).not.toHaveBeenCalled();
  expect(mocks.scanCertificateDirectory).not.toHaveBeenCalled();
});

it('does not auto-select or sign an expired PC certificate', async () => {
  const sign = vi.fn();
  mocks.discoverBridgeCertificates.mockImplementation(async (medium: string) => medium === 'hard-disk' ? [{
    id: 'bridge:expired', kind: 'NPKI', name: '만료 인증서', issuer: '인증기관',
    path: '이 PC의 NPKI', expiresAt: new Date(Date.now() - 86400000), source: 'LOCAL_BRIDGE', sign,
  }] : []);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'expired-cert', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  const row = await screen.findByRole('radio', { name: '만료 인증서 인증서 선택' });
  expect(row).not.toBeChecked();
  expect(row).toBeDisabled();
  fireEvent.change(screen.getByLabelText('인증서 비밀번호'), { target: { value: 'secret' } });
  expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
  expect(sign).not.toHaveBeenCalled();
});

it('locks the password until a certificate is selected and clears it when the selection changes', async () => {
  const signA = vi.fn();
  const signB = vi.fn();
  mocks.discoverBridgeCertificates.mockImplementation(async (medium: string) => medium === 'hard-disk' ? [
    { id: 'bridge:a', kind: 'NPKI', name: '인증서 A', issuer: 'yessign', path: '이 PC의 NPKI', source: 'LOCAL_BRIDGE', sign: signA },
    { id: 'bridge:b', kind: 'NPKI', name: '인증서 B', issuer: 'yessign', path: '이 PC의 NPKI', source: 'LOCAL_BRIDGE', sign: signB },
  ] : []);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  render(<ExternalCertificateProof challenge={{ challengeId: 'lock', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={async () => {}} />);
  await screen.findByRole('radio', { name: '인증서 A 인증서 선택' });
  const password = screen.getByLabelText('인증서 비밀번호');
  expect(password).toBeDisabled();
  expect(password).toHaveAttribute('placeholder', '먼저 위에서 인증서를 선택해 주세요');
  expect(screen.getByRole('button', { name: '가상키보드 열기' })).toBeDisabled();

  fireEvent.click(screen.getByRole('radio', { name: '인증서 A 인증서 선택' }));
  expect(password).toBeEnabled();
  fireEvent.change(password, { target: { value: 'for-a' } });
  fireEvent.click(screen.getByRole('radio', { name: '인증서 B 인증서 선택' }));
  await waitFor(() => expect(password).toHaveValue(''));
  expect(signA).not.toHaveBeenCalled();
  expect(signB).not.toHaveBeenCalled();
});

it('reports a password that does not open the selected certificate and sends nothing to INVIZ', async () => {
  const sign = vi.fn(async () => { throw new Error('인증서 비밀번호가 일치하지 않습니다. 선택한 인증서의 비밀번호를 다시 입력해 주세요.'); });
  mocks.discoverBridgeCertificates.mockImplementation(async (medium: string) => medium === 'hard-disk' ? [{
    id: 'bridge:only', kind: 'NPKI', name: '박판독', issuer: 'yessign', path: '이 PC의 NPKI', source: 'LOCAL_BRIDGE', sign,
  }] : []);
  mocks.getRememberedDirectory.mockResolvedValue(undefined);
  const onProof = vi.fn(async () => {});
  render(<ExternalCertificateProof challenge={{ challengeId: 'wrong', signedPayloadBase64: btoa('challenge'),
    expiresAt: new Date(Date.now() + 600000).toISOString() } as CertificateChallenge} busy={false} onProof={onProof} />);
  expect(await screen.findByRole('radio', { name: '박판독 인증서 선택' })).toBeChecked();
  fireEvent.change(screen.getByLabelText('인증서 비밀번호'), { target: { value: 'wrong' } });
  fireEvent.submit(screen.getByRole('button', { name: '확인' }).closest('form')!);
  expect(await screen.findByRole('alert')).toHaveTextContent('인증서 비밀번호가 일치하지 않습니다');
  expect(onProof).not.toHaveBeenCalled();
  expect(screen.getByLabelText('인증서 비밀번호')).toHaveValue('');
});
