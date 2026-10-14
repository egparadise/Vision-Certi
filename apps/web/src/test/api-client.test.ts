import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApiClient, ApiClientError } from '../api/client';

describe('ApiClient Unit Tests', () => {
  let client: ApiClient;
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    client = new ApiClient({
      baseUrl: '/v1',
      tenantId: 'demo-hospital-a',
      actorId: 'doctor-demo',
    });
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('injects X-Tenant-Id, X-Actor-Id, and X-Correlation-Id headers', async () => {
    let capturedHeaders: HeadersInit | undefined;
    globalThis.fetch = vi.fn().mockImplementation(async (_input, init) => {
      capturedHeaders = init?.headers;
      return new Response(JSON.stringify({ status: 'UP' }), { status: 200 });
    });

    await client.getDashboard();

    expect(capturedHeaders).toBeDefined();
    const headers = capturedHeaders as Record<string, string>;
    expect(headers['X-Tenant-Id']).toBe('demo-hospital-a');
    expect(headers['X-Actor-Id']).toBe('doctor-demo');
    expect(headers['X-Correlation-Id']).toMatch(/^web-/);
  });

  it('updates headers when setTenantId and setActorId are called', async () => {
    let capturedHeaders: HeadersInit | undefined;
    globalThis.fetch = vi.fn().mockImplementation(async (_input, init) => {
      capturedHeaders = init?.headers;
      return new Response(JSON.stringify({ items: [] }), { status: 200 });
    });

    client.setTenantId('demo-hospital-b');
    client.setActorId('admin-demo');

    await client.getDocuments();

    const headers = capturedHeaders as Record<string, string>;
    expect(headers['X-Tenant-Id']).toBe('demo-hospital-b');
    expect(headers['X-Actor-Id']).toBe('admin-demo');
  });

  it('parses structured API errors into ApiClientError', async () => {
    globalThis.fetch = vi.fn().mockImplementation(async () => {
      return new Response(
        JSON.stringify({
          code: 'SIGNING_NOT_PERMITTED_FOR_ROLE',
          message: '관리자 역할은 전자서명을 승인할 수 없습니다.',
          correlationId: 'corr-err-403',
          retryable: false,
        }),
        { status: 403 }
      );
    });

    await expect(
      client.approveSignRequest('req-1', 'hash-1', 'nonce-1', 'idem-1')
    ).rejects.toThrow('관리자 역할은 전자서명을 승인할 수 없습니다.');

    try {
      await client.approveSignRequest('req-1', 'hash-1', 'nonce-1', 'idem-1');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiClientError);
      const apiErr = err as ApiClientError;
      expect(apiErr.code).toBe('SIGNING_NOT_PERMITTED_FOR_ROLE');
      expect(apiErr.status).toBe(403);
      expect(apiErr.correlationId).toBe('corr-err-403');
      expect(apiErr.retryable).toBe(false);
    }
  });

  it('sends Idempotency-Key on sign approval', async () => {
    let capturedHeaders: HeadersInit | undefined;
    globalThis.fetch = vi.fn().mockImplementation(async (_input, init) => {
      capturedHeaders = init?.headers;
      return new Response(
        JSON.stringify({
          id: 'req-1',
          status: 'COMPLETED',
        }),
        { status: 202 }
      );
    });

    await client.approveSignRequest('req-1', 'sha256-hex', 'nonce-123', 'custom-idem-key-777');

    const headers = capturedHeaders as Record<string, string>;
    expect(headers['Idempotency-Key']).toBe('custom-idem-key-777');
    expect(headers['Content-Type']).toBe('application/json');
  });
});
