import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { ExternalCertificateProof } from '../components/ExternalCertificateProof';
import type { CertificateChallenge } from '../types/api';
import appPackage from '../../package.json';

const mocks = vi.hoisted(() => ({
  getRememberedDirectory: vi.fn(),
  rememberDirectory: vi.fn(),
  scanCertificateDirectory: vi.fn(),
  signWithCertificateFiles: vi.fn(),
}));
vi.mock('../services/localCertificateDiscovery', () => ({
  getRememberedDirectory: mocks.getRememberedDirectory,
  rememberDirectory: mocks.rememberDirectory,
  scanCertificateDirectory: mocks.scanCertificateDirectory,
}));
vi.mock('../services/certificateFileSigner', () => ({ signWithCertificateFiles: mocks.signWithCertificateFiles }));

it('shows an authorized certificate automatically and signs only after selection and password', async () => {
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
