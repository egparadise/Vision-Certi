import React, { useEffect, useState } from 'react';
import type { CertificateChallenge } from '../types/api';
import type { LocalSignResult } from '../services/localCertSdkAdapter';
import { signWithCertificateFiles, type CertificateFiles } from '../services/certificateFileSigner';
import { getRememberedDirectory, rememberDirectory, scanCertificateDirectory,
  type CertificateMedium, type DiscoveredCertificate, type ReadableDirectoryHandle } from '../services/localCertificateDiscovery';

type DirectoryPickerWindow = Window & { showDirectoryPicker?: (options: {
  id: string; mode: 'read'; startIn?: FileSystemDirectoryHandle | 'documents';
}) => Promise<ReadableDirectoryHandle> };
const MEDIA: CertificateMedium[] = ['hard-disk', 'removable-disk'];
const MEDIA_LABEL: Record<CertificateMedium, string> = { 'hard-disk': '하드디스크', 'removable-disk': '이동식디스크' };

/** The server-supplied exact bytes are signed locally; only public proof fields reach INVIZ. */
export function ExternalCertificateProof({ challenge, payloadBase64, expiresAt, actionLabel, busy, onProof }: {
  challenge?: CertificateChallenge; payloadBase64?: string; expiresAt?: string; actionLabel?: string;
  busy: boolean; onProof: (proof: LocalSignResult) => Promise<void>;
}) {
  const [medium, setMedium] = useState<CertificateMedium>('hard-disk');
  const [directories, setDirectories] = useState<Partial<Record<CertificateMedium, ReadableDirectoryHandle>>>({});
  const [permissionNeeded, setPermissionNeeded] = useState<Partial<Record<CertificateMedium, boolean>>>({});
  const [discovered, setDiscovered] = useState<Partial<Record<CertificateMedium, DiscoveredCertificate[]>>>({});
  const [selectedId, setSelectedId] = useState<string>();
  const [scanning, setScanning] = useState(false);
  const [manualMode, setManualMode] = useState(false);
  const [kind, setKind] = useState<'PFX' | 'NPKI'>('PFX');
  const [bundle, setBundle] = useState<File>();
  const [certificate, setCertificate] = useState<File>();
  const [privateKey, setPrivateKey] = useState<File>();
  const [chain, setChain] = useState<File>();
  const [password, setPassword] = useState('');
  const [signing, setSigning] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());
  const entries = discovered[medium] ?? [];
  const selected = entries.find(entry => entry.id === selectedId);
  const manualComplete = kind === 'PFX' ? Boolean(bundle) : Boolean(certificate && privateKey);
  const expires = Date.parse(challenge?.expiresAt ?? expiresAt ?? '');
  const expired = !Number.isFinite(expires) || now >= expires;

  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      for (const item of MEDIA) {
        try {
          const directory = await getRememberedDirectory(item);
          if (!directory || cancelled) continue;
          setDirectories(previous => ({ ...previous, [item]: directory }));
          if (await directory.queryPermission({ mode: 'read' }) !== 'granted') {
            if (!cancelled) setPermissionNeeded(previous => ({ ...previous, [item]: true }));
            continue;
          }
          const found = await scanCertificateDirectory(directory, item);
          if (!cancelled) setDiscovered(previous => ({ ...previous, [item]: found }));
        } catch {
          // Manual file selection remains available if a saved folder is inaccessible.
        }
      }
    })();
    return () => { cancelled = true; };
  }, []);

  async function chooseDirectory(reusePermission: boolean) {
    setError('');
    try {
      let directory: ReadableDirectoryHandle;
      if (reusePermission && directories[medium]) {
        directory = directories[medium];
        if (await directory.requestPermission({ mode: 'read' }) !== 'granted') return;
      } else {
        const picker = (window as DirectoryPickerWindow).showDirectoryPicker;
        if (!picker) throw new Error('이 브라우저는 폴더 검색을 지원하지 않습니다. Chrome 또는 Edge에서 열거나 아래에서 인증서 파일을 직접 선택해 주세요.');
        directory = await picker.call(window, { id: `vision-certi-${medium}`, mode: 'read',
          startIn: directories[medium] ?? 'documents' });
      }
      setScanning(true);
      const found = await scanCertificateDirectory(directory, medium);
      setDirectories(previous => ({ ...previous, [medium]: directory }));
      setPermissionNeeded(previous => ({ ...previous, [medium]: false }));
      setDiscovered(previous => ({ ...previous, [medium]: found }));
      setSelectedId(undefined);
      try { await rememberDirectory(medium, directory); } catch {
        setError('인증서 목록은 찾았지만 브라우저가 폴더 위치를 기억하지 못했습니다. 다음 방문 시 다시 선택해야 합니다.');
      }
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        setError(cause instanceof Error ? cause.message : '인증서 폴더를 열 수 없습니다.');
      }
    } finally { setScanning(false); }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    setError('');
    if (busy || signing || expired) return;
    try {
      let files: CertificateFiles;
      if (selected) files = await selected.getFiles();
      else if (manualMode && kind === 'PFX' && bundle) files = { kind, bundle };
      else if (manualMode && kind === 'NPKI' && certificate && privateKey) files = { kind, certificate, privateKey, chain };
      else throw new Error('목록에서 인증서를 선택하거나 인증서 파일을 직접 선택해 주세요.');
      setSigning(true);
      const toSign = challenge?.signedPayloadBase64 ?? payloadBase64;
      if (!toSign) throw new Error('서명할 인증 요청이 없습니다. 다시 시도해 주세요.');
      await onProof(await signWithCertificateFiles(files, password, toSign));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '인증서 서명에 실패했습니다.');
    } finally {
      setSigning(false); setPassword(''); setBundle(undefined); setCertificate(undefined);
      setPrivateKey(undefined); setChain(undefined);
      form.querySelectorAll<HTMLInputElement>('input[type="file"]').forEach(input => { input.value = ''; });
    }
  }

  return <section className="account-proof" aria-label={actionLabel ?? '공동인증서 선택 및 로그인'}>
    <p>PC에 보관된 공동인증서를 선택하고 비밀번호를 입력해 주세요. 인증서 개인키와 비밀번호는 서버로 전송되지 않습니다.</p>
    <p role="status">{expired ? '인증 요청이 만료되었습니다. 새 요청을 시작해 주세요.' : `남은 시간 ${Math.ceil((expires - now) / 1000)}초`}</p>
    <form onSubmit={submit}>
      <fieldset disabled={busy || signing || expired} className="enroll-form-fields">
        <legend className="enroll-sr-only">공동인증서</legend>
        <div className="cert-media" aria-label="인증서 저장매체">
          {MEDIA.map(item => <button key={item} type="button" aria-pressed={medium === item}
            onClick={() => { setMedium(item); setSelectedId(undefined); setManualMode(false); setError(''); }}>
            <span aria-hidden="true">{item === 'hard-disk' ? '▣' : '▤'}</span> {MEDIA_LABEL[item]}
          </button>)}
        </div>
        <div className="cert-location-actions">
          <button type="button" onClick={() => void chooseDirectory(false)} disabled={scanning}>인증서 폴더 찾기</button>
          {permissionNeeded[medium] && <button type="button" onClick={() => void chooseDirectory(true)} disabled={scanning}>저장된 폴더 접근 허용</button>}
          {directories[medium] && !permissionNeeded[medium] && <button type="button" onClick={() => void chooseDirectory(true)} disabled={scanning}>목록 새로고침</button>}
        </div>
        <p className="cert-location-help">{MEDIA_LABEL[medium]}의 NPKI 폴더 또는 PFX/P12가 있는 폴더를 선택하세요. 한 번 허용한 폴더는 다음 방문에 다시 확인합니다.</p>
        <div className="cert-list-wrap">
          <table className="cert-list">
            <thead><tr><th>선택</th><th>구분</th><th>사용자 / 인증서</th><th>만료일</th><th>발급기관</th></tr></thead>
            <tbody>{entries.map(entry => {
              const isExpired = Boolean(entry.expiresAt && entry.expiresAt.getTime() <= now);
              return <tr key={entry.id} className={selectedId === entry.id ? 'selected' : ''}>
                <td><input type="radio" name="local-certificate" aria-label={`${entry.name} 인증서 선택`} checked={selectedId === entry.id}
                  disabled={isExpired} onChange={() => { setSelectedId(entry.id); setManualMode(false); }} /></td>
                <td>{entry.kind === 'NPKI' ? '공동' : 'PFX/P12'}</td>
                <td><strong>{entry.name}</strong><small title={entry.path}>{entry.path || '선택한 폴더'}</small></td>
                <td>{entry.expiresAt ? entry.expiresAt.toLocaleDateString('ko-KR') : '확인 전'}{isExpired ? ' (만료)' : ''}</td>
                <td>{entry.issuer}</td>
              </tr>;
            })}</tbody>
          </table>
          {scanning && <p aria-live="polite">인증서 검색 중…</p>}
          {!scanning && entries.length === 0 && <p className="cert-empty">{permissionNeeded[medium] ? '저장된 폴더의 접근을 다시 허용해 주세요.' :
            directories[medium] ? '선택한 폴더에서 NPKI 인증서 또는 PFX/P12 파일을 찾지 못했습니다.' : '인증서 폴더를 선택하면 이곳에 목록이 표시됩니다.'}</p>}
        </div>
        <details className="cert-manual" onToggle={event => { if (event.currentTarget.open) { setSelectedId(undefined); setManualMode(true); } }}>
          <summary>목록에 없나요? 인증서 파일 직접 선택</summary>
          <label>파일 형식<select value={kind} onChange={event => { setKind(event.target.value as 'PFX' | 'NPKI'); setManualMode(true); setSelectedId(undefined); }}>
            <option value="PFX">PFX / P12</option><option value="NPKI">NPKI (signCert.der + signPri.key)</option>
          </select></label>
          {kind === 'PFX' ? <label>PFX/P12 파일<input type="file" accept=".pfx,.p12,application/x-pkcs12"
            onChange={event => { setBundle(event.target.files?.[0]); setManualMode(true); setSelectedId(undefined); }} /></label> : <>
            <label>공개 인증서 (signCert.der)<input type="file" accept=".der" onChange={event => { setCertificate(event.target.files?.[0]); setManualMode(true); setSelectedId(undefined); }} /></label>
            <label>암호화된 개인키 (signPri.key)<input type="file" accept=".key" onChange={event => { setPrivateKey(event.target.files?.[0]); setManualMode(true); setSelectedId(undefined); }} /></label>
            <label>중간 인증기관 인증서 (있는 경우)<input type="file" accept=".der" onChange={event => setChain(event.target.files?.[0])} /></label>
          </>}
        </details>
        <label>인증서 비밀번호<input type="password" autoComplete="off" value={password} onChange={event => setPassword(event.target.value)} required /></label>
        <button className="enroll-primary" disabled={!password || (!selected && !(manualMode && manualComplete)) || signing}>
          {signing ? '인증서 확인 중…' : actionLabel ?? '인증서로 계속'}
        </button>
      </fieldset>
    </form>
    {error && <p role="alert">{error}</p>}
  </section>;
}
