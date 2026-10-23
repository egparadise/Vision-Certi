/** Shared checks for the popups a PACS window opens at `#/certificates/pacs-*`. */
export type PacsOpenerContext = {
  origin: string;
  nonce: string;
  audience: string;
  opener: Window;
  codeChallenge?: string;
  /** An installed WebView2 (Tauri) PACS: a script-initiated window.close() there leaves an empty native frame. */
  nativeOpener: boolean;
};

const NATIVE_PACS_ORIGINS = ['http://tauri.localhost', 'https://tauri.localhost'];

export function pacsPopupQuery(): URLSearchParams {
  return new URLSearchParams(window.location.hash.split('?')[1] || '');
}

/**
 * The host index.html redirected this popup away from. The PACS still expects messages from that origin, so nothing
 * posted from here can reach it; the PACS must be reconfigured to the current address instead.
 */
export function pacsRedirectedFromHost(): string | null {
  const host = pacsPopupQuery().get('vcRedirectedFrom');
  return host && /^[a-z0-9.-]{1,253}$/i.test(host) ? host : null;
}

export function pacsOpenerContext(): PacsOpenerContext | null {
  const query = pacsPopupQuery();
  const nonce = query.get('nonce');
  const audience = query.get('audience');
  const requestedOrigin = query.get('origin');
  const codeChallenge = query.get('codeChallenge') ?? undefined;
  if ((query.has('codeChallenge') || query.has('codeChallengeMethod')) &&
    (!codeChallenge || !/^[A-Za-z0-9_-]{43}$/.test(codeChallenge) || query.get('codeChallengeMethod') !== 'S256')) return null;
  if (!window.opener || window.opener.closed || !nonce || !/^[0-9a-f]{64}$/.test(nonce)
    || !audience || !/^[a-zA-Z0-9._:-]{1,128}$/.test(audience) || !requestedOrigin) return null;
  try {
    const origin = new URL(requestedOrigin).origin;
    const allowed = (import.meta.env.VITE_PACS_ALLOWED_ORIGINS || '').split(',').map((value: string) => value.trim());
    if (origin === 'null' || requestedOrigin !== origin
      || !(origin === window.location.origin || allowed.includes(origin))) return null;
    return { origin, nonce, audience, opener: window.opener, codeChallenge, nativeOpener: NATIVE_PACS_ORIGINS.includes(origin) };
  } catch { return null; }
}
