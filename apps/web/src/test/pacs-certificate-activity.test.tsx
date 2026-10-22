import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PacsCertificateActivity } from '../components/PacsCertificateActivity';
import type { PacsActivityPage } from '../types/pacsActivity';

const state = vi.hoisted(() => ({ client: { getPacsCertificateActivity: vi.fn() }, tenantId: 'inviz' }));
vi.mock('../context/AppContext', () => ({ useApp: () => state }));
const page: PacsActivityPage = {
  totals: { requested: 2, attempted: 1, verified: 1, redeemed: 1, checked: 0, denied: 0, loginAttempted: 2, loginSucceeded: 1, loginFailed: 1, approvalRequests: 3, approvalDocuments: 2 },
  certificates: [{ fingerprintSha256: 'a'.repeat(64), subjectDn: 'CN=검증 의사', issuerDn: 'CN=검증 기관',
    notAfter: '2027-10-22T00:00:00Z', verified: 1, redeemed: 1, checked: 0, lastSeenAt: '2026-10-22T00:00:00Z' }],
  events: [{ id: 'event-1', occurredAt: '2026-10-22T00:00:00Z', action: 'REDEEMED', outcome: 'SUCCESS',
    targetId: 'proof-1', correlationId: 'trace-1', legacy: false,
    details: { clientId: 'vision-certi-pacs', subjectDn: 'CN=검증 의사', peerIp: '10.0.0.1', forwardedFor: '203.0.113.8', pacsLoginId: 'reader42', computerName: 'READING-PC' } }],
  total: 51, offset: 0, limit: 50,
};

beforeEach(() => { state.tenantId = 'inviz'; state.client.getPacsCertificateActivity.mockReset().mockResolvedValue(page); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('PACS permanent activity dashboard', () => {
  it('shows receipt and certificate information, labels forwarded IPs, searches and pages on the server', async () => {
    render(<PacsCertificateActivity />);
    expect(await screen.findByText('vision-certi-pacs')).toBeInTheDocument();
    expect(screen.getByText(/직접: 10.0.0.1/)).toBeInTheDocument();
    expect(screen.getByText('(미검증)')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '요청 계정 / PC' })).toBeInTheDocument();
    expect(screen.getAllByText('reader42').length).toBeGreaterThan(0);
    expect(screen.getByText('승인 요청 판독문')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => expect(state.client.getPacsCertificateActivity).toHaveBeenLastCalledWith('', 50));
    fireEvent.change(screen.getByLabelText('인증 기록 검색'), { target: { value: '203.0.113.8' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));
    await waitFor(() => expect(state.client.getPacsCertificateActivity).toHaveBeenLastCalledWith('203.0.113.8', 0));
    expect(screen.getByText('trace-1')).toBeInTheDocument();
  });

  it('refreshes every 30 seconds and stops polling after unmount', async () => {
    vi.useFakeTimers();
    const view = render(<PacsCertificateActivity />);
    await act(async () => {});
    expect(state.client.getPacsCertificateActivity).toHaveBeenCalledTimes(1);
    await act(async () => { vi.advanceTimersByTime(30_000); });
    expect(state.client.getPacsCertificateActivity).toHaveBeenCalledTimes(2);
    view.unmount();
    await act(async () => { vi.advanceTimersByTime(60_000); });
    expect(state.client.getPacsCertificateActivity).toHaveBeenCalledTimes(2);
  });

  it('discards previous tenant responses', async () => {
    let resolveOld!: (value: PacsActivityPage) => void;
    state.client.getPacsCertificateActivity.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const view = render(<PacsCertificateActivity />);
    state.tenantId = 'another-tenant';
    state.client.getPacsCertificateActivity.mockResolvedValue({ ...page, total: 0, certificates: [], events: [] });
    view.rerender(<PacsCertificateActivity />);
    await screen.findByText('기록된 PACS 인증 요청이 없습니다.');
    await act(async () => { resolveOld(page); });
    expect(screen.queryByText('vision-certi-pacs')).not.toBeInTheDocument();
  });
});
