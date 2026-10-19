export async function discoverOidc(issuer: string) {
  const configured = new URL(issuer);
  const validEndpoint = (value: unknown): value is string => {
    if (typeof value !== 'string') return false;
    const url = new URL(value);
    return !url.username && !url.password && !url.hash &&
      (url.protocol === 'https:' || (import.meta.env.DEV && url.protocol === 'http:' &&
        ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)));
  };
  if (!validEndpoint(configured.href)) throw new Error('OIDC issuer must use HTTPS');
  const response = await fetch(issuer.replace(/\/$/, '') + '/.well-known/openid-configuration', {
    credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error('OIDC discovery failed');
  const metadata = await response.json();
  if (metadata.issuer !== issuer || !validEndpoint(metadata.authorization_endpoint) ||
      !validEndpoint(metadata.token_endpoint) || !metadata.code_challenge_methods_supported?.includes('S256')) {
    throw new Error('OIDC metadata issuer, endpoints or PKCE S256 are invalid');
  }
  return { authorizationEndpoint: metadata.authorization_endpoint as string,
    tokenEndpoint: metadata.token_endpoint as string };
}
