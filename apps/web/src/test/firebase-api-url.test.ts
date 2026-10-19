import { describe, expect, it } from 'vitest';
import { apiBaseUrl } from '../api/baseUrl';

describe('Firebase-hosted API URL', () => {
  it('keeps local and same-origin deployments on /v1', () => {
    expect(apiBaseUrl(undefined)).toBe('/v1');
    expect(apiBaseUrl('')).toBe('/v1');
  });

  it('accepts an explicit HTTPS API URL', () => {
    expect(apiBaseUrl('https://auth.example.org/v1/')).toBe('https://auth.example.org/v1');
  });

  it('rejects insecure or ambiguous endpoints', () => {
    for (const input of ['http://auth.example.org/v1', 'https://auth.example.org/other',
      'https://user:pass@auth.example.org/v1', 'https://auth.example.org/v1?token=x']) {
      expect(() => apiBaseUrl(input)).toThrow('VITE_API_BASE_URL');
    }
  });
});
