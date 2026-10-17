import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { ExternalCertificateProof } from '../components/ExternalCertificateProof';
import type { CertificateChallenge } from '../types/api';

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

  const row = await screen.findByRole('radio', { name: 'doctor.p12 인증서 선택' });
  expect(screen.getByRole('button', { name: '인증서로 계속' })).toBeDisabled();
  expect(getFiles).not.toHaveBeenCalled();
  fireEvent.click(row);
  fireEvent.change(screen.getByLabelText('인증서 비밀번호'), { target: { value: 'secret' } });
  fireEvent.submit(screen.getByRole('button', { name: '인증서로 계속' }).closest('form')!);
  await waitFor(() => expect(onProof).toHaveBeenCalledWith(proof));
  expect(mocks.signWithCertificateFiles).toHaveBeenCalledWith({ kind: 'PFX', bundle }, 'secret', btoa('challenge'));
  expect(getFiles).toHaveBeenCalledTimes(1);
  expect(screen.getByLabelText('인증서 비밀번호')).toHaveValue('');
});
