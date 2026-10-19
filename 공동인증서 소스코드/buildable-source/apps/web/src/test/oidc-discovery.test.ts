import { afterEach, describe, expect, it, vi } from 'vitest';
import { discoverOidc } from '../services/oidcDiscovery';

const issuer = 'https://identity.example.invalid/tenant';
const metadata = { issuer, authorization_endpoint: issuer + '/authorize',
  token_endpoint: issuer + '/token', code_challenge_methods_supported: ['S256'] };
afterEach(() => vi.unstubAllGlobals());
describe('OIDC discovery trust boundary', () => {
  it('uses discovered endpoints instead of assuming a vendor URL', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => metadata }));
    expect(await discoverOidc(issuer)).toEqual({ authorizationEndpoint: issuer + '/authorize', tokenEndpoint: issuer + '/token' });
  });
  it.each([
    { issuer: 'https://other.example.invalid' },
    { token_endpoint: 'http://identity.example.invalid/token' },
    { code_challenge_methods_supported: ['plain'] },
  ])('rejects untrusted or unsuitable discovery metadata %j', async (overrides) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ...metadata, ...overrides }) }));
    await expect(discoverOidc(issuer)).rejects.toThrow('OIDC metadata');
  });
});
