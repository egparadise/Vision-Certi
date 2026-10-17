import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PhysicianAccountView } from '../components/PhysicianAccountView';
import { ExternalCertificateProof } from '../components/ExternalCertificateProof';
import type { CertificateChallenge } from '../types/api';

const mocks = vi.hoisted(() => ({ client: {
  getPhysicianAccountConfig: vi.fn(), registerPhysicianAccount: vi.fn(), resumePhysicianAccount: vi.fn(),
  createPhysicianAccountChallenge: vi.fn(), logoutPhysicianAccount: vi.fn(),
} }));
vi.mock('../context/AppContext', () => ({ useApp: () => ({ client: mocks.client }) }));
vi.mock('../components/CertificateEnrollmentView', () => ({ CertificateEnrollmentView: ({ proofMethod }: {proofMethod: string}) => <div>인증서 확인 방식: {proofMethod}</div> }));
const receipt = { enrollmentToken: 'vca1.secret-test-token', expiresAt: new Date(Date.now()+600000).toISOString(), account: {
  accountId: 'account', username: 'physician123', scope: 'ENROLLMENT_ONLY', phoneVerified: false,
  doctor: { id: 'doctor', name: '시험의사', status: 'PENDING' },
} };
beforeEach(() => {
  vi.clearAllMocks();
  mocks.client.getPhysicianAccountConfig.mockResolvedValue({ available: true, consentVersion: 'current', scope: 'ENROLLMENT_ONLY' });
  mocks.client.registerPhysicianAccount.mockResolvedValue(receipt);
  mocks.client.resumePhysicianAccount.mockResolvedValue(receipt);
  mocks.client.createPhysicianAccountChallenge.mockResolvedValue({ challengeId: 'challenge' });
  mocks.client.logoutPhysicianAccount.mockResolvedValue(undefined);
});
describe('Physician account onboarding', () => {
  it('registers without SDK, clears password, holds only limited token in memory and starts proof', async () => {
    render(<PhysicianAccountView />);
    const button = await screen.findByRole('button', { name: '가입하고 인증서 등록 준비' });
    await waitFor(() => expect(screen.getByLabelText(/^ID /)).not.toBeDisabled());
    for (const [label, value] of [[/^ID /, 'physician123'], [/^비밀번호 \(/, 'safe-test-password'], [/^비밀번호 확인/, 'safe-test-password'],
      [/^이름/, '시험의사'], [/^연락처/, '01012345678'], [/^의사면허번호/, '123456'], [/^소속 기관/, '시험기관']] as const) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
    }
    expect(button).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(button);
    await screen.findByRole('heading', { name: '시험의사님의 계정' });
    expect(mocks.client.registerPhysicianAccount).toHaveBeenCalledWith(expect.objectContaining({ privacyConsent: true, consentVersion: 'current', username: 'physician123' }));
    expect(screen.queryByLabelText(/^비밀번호 \(/)).not.toBeInTheDocument();
    expect(JSON.stringify(localStorage)).not.toContain(receipt.enrollmentToken);
    expect(JSON.stringify(sessionStorage)).not.toContain(receipt.enrollmentToken);
    expect(window.location.href).not.toContain(receipt.enrollmentToken);
    fireEvent.click(screen.getByRole('button', { name: '인증서 등록·재개 요청' }));
    await screen.findByText('인증서 확인 방식: external');
    expect(mocks.client.createPhysicianAccountChallenge).toHaveBeenCalledWith(receipt.enrollmentToken);
    fireEvent.click(screen.getByRole('button', { name: '가입 세션 종료' }));
    await waitFor(() => expect(screen.queryByText('인증서 확인 방식: external')).not.toBeInTheDocument());
    expect(mocks.client.logoutPhysicianAccount).toHaveBeenCalledWith(receipt.enrollmentToken);
  });
  it('shows failed password login without authenticated state or certificate step', async () => {
    mocks.client.resumePhysicianAccount.mockRejectedValue(new Error('계정 정보가 올바르지 않습니다.'));
    render(<PhysicianAccountView />);
    fireEvent.click(screen.getByRole('button', { name: '가입 이어하기' }));
    await waitFor(() => expect(screen.getByLabelText(/^ID /)).not.toBeDisabled());
    fireEvent.change(screen.getByLabelText(/^ID /), { target: { value: 'physician123' } });
    fireEvent.change(screen.getByLabelText(/^비밀번호 \(/), { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: '가입 절차 이어하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('올바르지');
    expect(screen.queryByText('인증서 등록·재개 요청')).not.toBeInTheDocument();
  });
  it('keeps signup unavailable when configuration fails', async () => {
    mocks.client.getPhysicianAccountConfig.mockRejectedValue(new Error('서버 연결 실패'));
    render(<PhysicianAccountView />);
    await screen.findByRole('alert');
    expect(screen.getByLabelText(/^ID /)).toBeDisabled();
    expect(mocks.client.registerPhysicianAccount).not.toHaveBeenCalled();
  });
});
describe('External proof input', () => {
  const challenge = { challengeId: 'test', signedPayloadBase64: btoa('exact\nbytes'), expiresAt: new Date(Date.now()+600000).toISOString() } as CertificateChallenge;
  it('requires a local certificate and password before sending any proof', async () => {
    const onProof = vi.fn();
    render(<ExternalCertificateProof challenge={challenge} busy={false} onProof={onProof} />);
    expect(screen.getByLabelText('PFX/P12 파일')).toBeInTheDocument();
    expect(screen.getByLabelText('인증서 비밀번호')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '인증서로 계속' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('파일 형식'), { target: { value: 'NPKI' } });
    expect(screen.getByLabelText(/signCert.der/)).toBeInTheDocument();
    expect(screen.getByLabelText(/signPri.key/)).toBeInTheDocument();
    expect(onProof).not.toHaveBeenCalled();
  });
  it('does not submit after challenge expiry', () => {
    const onProof = vi.fn();
    render(<ExternalCertificateProof challenge={{ ...challenge, expiresAt: new Date(Date.now()-1000).toISOString() }} busy={false} onProof={onProof} />);
    expect(screen.getByRole('status')).toHaveTextContent('만료');
    expect(screen.getByRole('button', { name: '인증서로 계속' })).toBeDisabled();
    expect(onProof).not.toHaveBeenCalled();
  });
});
