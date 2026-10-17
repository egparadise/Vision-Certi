import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { ExternalCertificateProof } from '../components/ExternalCertificateProof';
import type { CertificateChallenge } from '../types/api';
import appPackage from '../../package.json';

const mocks = vi.hoisted(() => ({
  getRememberedDirectory: vi.fn(),
  rememberDirectory: vi.fn(),
  scanCertificateDirectory: vi.fn(),
  signWithCertificateFiles: vi.fn(),
  discoverBridgeCertificates: vi.fn(),
}));
vi.mock('../services/localCertificateDiscovery', () => ({
  getRememberedDirectory: mocks.getRememberedDirectory,
  rememberDirectory: mocks.rememberDirectory,
  scanCertificateDirectory: mocks.scanCertificateDirectory,
}));
vi.mock('../services/certificateFileSigner', () => ({ signWithCertificateFiles: mocks.signWithCertificateFiles }));
vi.mock('../services/localCertificateBridge', () => ({ discoverBridgeCertificates: mocks.discoverBridgeCertificates }));
beforeEach(() => vi.clearAllMocks());

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
  await screen.findByText('처음 사용할 때 검색할 PC 폴더를 한 번 선택해 주세요.');
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
