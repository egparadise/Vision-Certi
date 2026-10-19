/** The browser-facing API URL. Keep same-origin /v1 unless an HTTPS API endpoint is configured at build time. */
export function apiBaseUrl(configured = import.meta.env.VITE_API_BASE_URL): string {
  const value = configured?.trim();
  if (!value) return '/v1';
  try {
    const url = new URL(value);
    const localDev = import.meta.env.DEV && url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname);
    if ((url.protocol !== 'https:' && !localDev) || url.username || url.password || url.search || url.hash
      || url.pathname.replace(/\/$/, '') !== '/v1') throw new Error();
    return url.toString().replace(/\/$/, '');
  } catch {
    throw new Error('VITE_API_BASE_URL must be an HTTPS URL ending in /v1.');
  }
}
