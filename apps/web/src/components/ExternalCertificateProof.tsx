import React, { useEffect, useRef, useState } from 'react';
import { FolderSearch, HardDrive, Keyboard, Usb, X } from 'lucide-react';
import appPackage from '../../package.json';
import { CertificateVirtualKeyboard } from './CertificateVirtualKeyboard';
import type { CertificateChallenge } from '../types/api';
import type { LocalSignResult } from '../services/localCertSdkAdapter';
import { PASSWORD_MISMATCH, signWithCertificateFiles, type CertificateFiles } from '../services/certificateFileSigner';
import { discoverBridgeCertificates, type BridgeCertificate } from '../services/localCertificateBridge';
import { getRememberedDirectory, rememberDirectory, scanCertificateDirectory,
  type CertificateMedium, type DiscoveredCertificate, type ReadableDirectoryHandle } from '../services/localCertificateDiscovery';
import './physician-account.css';

type DirectoryPickerWindow = Window & { showDirectoryPicker?: (options: {
  id: string; mode: 'read'; startIn?: FileSystemDirectoryHandle | 'documents';
}) => Promise<ReadableDirectoryHandle> };
const MEDIA: CertificateMedium[] = ['hard-disk', 'removable-disk'];
const MEDIA_LABEL: Record<CertificateMedium, string> = { 'hard-disk': '하드디스크', 'removable-disk': '이동식디스크' };
type CertificateChoice = DiscoveredCertificate | BridgeCertificate;

/** The server-supplied exact bytes are signed locally; only public proof fields reach INVIZ. */
export function ExternalCertificateProof({ challenge, payloadBase64, expiresAt, actionLabel, busy, onProof, onCancel }: {
  challenge?: CertificateChallenge; payloadBase64?: string; expiresAt?: string; actionLabel?: string;
  busy: boolean; onProof: (proof: LocalSignResult) => Promise<void>; onCancel?: () => void;
}) {
  const [medium, setMedium] = useState<CertificateMedium>('hard-disk');
  const [directories, setDirectories] = useState<Partial<Record<CertificateMedium, ReadableDirectoryHandle>>>({});
  const [permissionNeeded, setPermissionNeeded] = useState<Partial<Record<CertificateMedium, boolean>>>({});
  const [discovered, setDiscovered] = useState<Partial<Record<CertificateMedium, CertificateChoice[]>>>({});
  const [scanLimited, setScanLimited] = useState<Partial<Record<CertificateMedium, boolean>>>({});
  const scanGeneration = useRef(0);
  const [selectedId, setSelectedId] = useState<string>();
  const [scanning, setScanning] = useState(false);
  const [scanRetry, setScanRetry] = useState(0);
  const [bridgeState, setBridgeState] = useState<'checking' | 'available' | 'unavailable'>('checking');
  const [manualMode, setManualMode] = useState(false);
  const [kind, setKind] = useState<'PFX' | 'NPKI'>('PFX');
  const [bundle, setBundle] = useState<File>();
  const [certificate, setCertificate] = useState<File>();
  const [privateKey, setPrivateKey] = useState<File>();
  const [chain, setChain] = useState<File>();
  const [password, setPassword] = useState('');
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const passwordInput = useRef<HTMLInputElement>(null);
  const virtualCaret = useRef<number | null>(null);
  const [signing, setSigning] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());
  const [passwordFailures, setPasswordFailures] = useState(0);
  const [lockedUntil, setLockedUntil] = useState(0);
  const entries = discovered[medium] ?? [];
  const selected = entries.find(entry => entry.id === selectedId);
  const selectedExpired = Boolean(selected?.expiresAt && selected.expiresAt.getTime() <= now);
  const manualComplete = kind === 'PFX' ? Boolean(bundle) : Boolean(certificate && privateKey);
  /** The password belongs to one certificate: it can be typed only after a usable certificate is chosen. */
  const certificateChosen = selected ? !selectedExpired : manualMode && manualComplete;
  const chosenKey = selected ? selected.id : manualMode && manualComplete ? `manual:${kind}:${bundle?.name ?? ''}:${certificate?.name ?? ''}:${privateKey?.name ?? ''}` : '';
  const passwordLockKey = `vision-certi:cert-password-attempts:${chosenKey}`;
  const passwordLocked = lockedUntil > now;
  const expiryWarning = selected?.expiresAt && selected.expiresAt.getTime() > now
    && selected.expiresAt.getTime() - now <= 30 * 24 * 60 * 60 * 1000;
  const expires = Date.parse(challenge?.expiresAt ?? expiresAt ?? '');
  const expired = !Number.isFinite(expires) || now >= expires;

  function insertPassword(character: string) {
    const input = passwordInput.current;
    const start = virtualCaret.current ?? input?.selectionStart ?? password.length;
    const end = virtualCaret.current ?? input?.selectionEnd ?? start;
    const nextCaret = start + character.length;
    virtualCaret.current = nextCaret;
    setPassword(password.slice(0, start) + character + password.slice(end));
    window.setTimeout(() => {
      input?.focus(); input?.setSelectionRange(nextCaret, nextCaret);
      if (virtualCaret.current === nextCaret) virtualCaret.current = null;
    }, 0);
  }

  function deletePasswordCharacter() {
    const input = passwordInput.current;
    const end = virtualCaret.current ?? input?.selectionEnd ?? password.length;
    const start = virtualCaret.current ?? input?.selectionStart ?? end;
    const from = start === end ? Math.max(0, start - 1) : start;
    virtualCaret.current = from;
    setPassword(password.slice(0, from) + password.slice(end));
    window.setTimeout(() => {
      input?.focus(); input?.setSelectionRange(from, from);
      if (virtualCaret.current === from) virtualCaret.current = null;
    }, 0);
  }

  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  // A password typed for one certificate is never reused for another.
  useEffect(() => {
    setPassword(''); virtualCaret.current = null; if (!chosenKey) setKeyboardOpen(false);
    try {
      const prior = JSON.parse(window.localStorage.getItem(passwordLockKey) || '{}') as { count?: number; until?: number };
      setPasswordFailures(prior.count ?? 0); setLockedUntil(prior.until ?? 0);
    } catch { setPasswordFailures(0); setLockedUntil(0); }
  }, [chosenKey, passwordLockKey]);
  useEffect(() => {
    let cancelled = false;
    const generation = ++scanGeneration.current;
    void (async () => {
      setScanning(true);
      setBridgeState('checking');
      for (const item of [medium]) {
        try {
          const local = await discoverBridgeCertificates(item);
          if (cancelled || generation !== scanGeneration.current) continue;
          setBridgeState('available');
          if (local.length) {
            setDiscovered(previous => ({ ...previous, [item]: local }));
            if (item === 'hard-disk' && local.length === 1 && (!local[0].expiresAt || local[0].expiresAt.getTime() > Date.now()))
              setSelectedId(local[0].id);
            continue;
          }
          const directory = await getRememberedDirectory(item);
          if (!directory || cancelled || generation !== scanGeneration.current) continue;
          setDirectories(previous => ({ ...previous, [item]: directory }));
          if (await directory.queryPermission({ mode: 'read' }) !== 'granted') {
            if (!cancelled && generation === scanGeneration.current) setPermissionNeeded(previous => ({ ...previous, [item]: true }));
            continue;
          }
          setDiscovered(previous => ({ ...previous, [item]: [] }));
          const found = await scanCertificateDirectory(directory, item, {
            shouldStop: () => cancelled || generation !== scanGeneration.current,
            onFound: entry => {
              if (!cancelled && generation === scanGeneration.current) setDiscovered(previous =>
                ({ ...previous, [item]: [...(previous[item] ?? []), entry] }));
            },
            onLimit: () => {
              if (!cancelled && generation === scanGeneration.current) setScanLimited(previous => ({ ...previous, [item]: true }));
            },
          });
          if (!cancelled && generation === scanGeneration.current) setDiscovered(previous => ({ ...previous, [item]: found }));
        } catch {
          if (!cancelled && generation === scanGeneration.current) setBridgeState('unavailable');
          // Manual file selection remains available if the local bridge or a saved folder is inaccessible.
        }
      }
      if (!cancelled && generation === scanGeneration.current) setScanning(false);
    })();
    return () => { cancelled = true; scanGeneration.current++; };
  }, [scanRetry, medium]);

  async function chooseDirectory(reusePermission: boolean) {
    setError('');
    const item = medium;
    const generation = ++scanGeneration.current;
    try {
      let directory: ReadableDirectoryHandle;
      if (reusePermission && directories[item]) {
        directory = directories[item];
        if (await directory.requestPermission({ mode: 'read' }) !== 'granted') return;
      } else {
        const picker = (window as DirectoryPickerWindow).showDirectoryPicker;
        if (!picker) throw new Error('이 브라우저는 폴더 검색을 지원하지 않습니다. Chrome 또는 Edge에서 열거나 아래에서 인증서 파일을 직접 선택해 주세요.');
        directory = await picker.call(window, { id: `vision-certi-${item}`, mode: 'read',
          startIn: directories[item] ?? 'documents' });
      }
      setScanning(true);
      setScanLimited(previous => ({ ...previous, [item]: false }));
      setDiscovered(previous => ({ ...previous, [item]: [] }));
      const found = await scanCertificateDirectory(directory, item, {
        shouldStop: () => generation !== scanGeneration.current,
        onFound: entry => {
          if (generation === scanGeneration.current) setDiscovered(previous =>
            ({ ...previous, [item]: [...(previous[item] ?? []), entry] }));
        },
        onLimit: () => {
          if (generation === scanGeneration.current) setScanLimited(previous => ({ ...previous, [item]: true }));
        },
      });
      if (generation !== scanGeneration.current) return;
      setDirectories(previous => ({ ...previous, [item]: directory }));
      setPermissionNeeded(previous => ({ ...previous, [item]: false }));
      setDiscovered(previous => ({ ...previous, [item]: found }));
      setSelectedId(undefined);
      try { await rememberDirectory(item, directory); } catch {
        setError('인증서 목록은 찾았지만 브라우저가 폴더 위치를 기억하지 못했습니다. 다음 방문 시 다시 선택해야 합니다.');
      }
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        setError(cause instanceof DOMException && (cause.name === 'SecurityError' || cause.name === 'NotAllowedError')
          ? '브라우저가 시스템 인증서 폴더의 접근을 차단했습니다. 아래 PC 인증서 프로그램을 설치하면 폴더를 고르지 않고 자동 검색합니다.'
          : cause instanceof Error ? cause.message : '인증서 폴더를 열 수 없습니다.');
      }
    } finally { if (generation === scanGeneration.current) setScanning(false); }
  }

  function notePasswordFailure(cause: unknown): never {
    const message = cause instanceof Error ? cause.message : '';
    if (message === PASSWORD_MISMATCH || /비밀번호|암호|password|decrypt/i.test(message)) {
      const count = lockedUntil && Date.now() >= lockedUntil ? 1 : passwordFailures + 1;
      const until = count >= 10 ? Date.now() + 10 * 60 * 1000 : 0;
      setPasswordFailures(count); setLockedUntil(until);
      window.localStorage.setItem(passwordLockKey, JSON.stringify({ count, until }));
      throw new Error(until ? '비밀번호 오류가 10회 발생하여 10분 동안 잠겼습니다.' : `${message} (${count}/10회)`);
    }
    throw cause;
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    setError('');
    if (busy || signing || expired) return;
    try {
      if (passwordLocked) throw new Error(`인증서 비밀번호 입력이 잠겼습니다. ${Math.ceil((lockedUntil - now) / 60000)}분 뒤 다시 시도해 주세요.`);
      if (selectedExpired) throw new Error('선택한 인증서가 만료되었습니다. 다른 인증서를 선택해 주세요.');
      if (!certificateChosen) throw new Error('먼저 인증서를 선택해 주세요.');
      setSigning(true);
      const toSign = challenge?.signedPayloadBase64 ?? payloadBase64;
      if (!toSign) throw new Error('서명할 인증 요청이 없습니다. 다시 시도해 주세요.');
      let signed: LocalSignResult;
      if (selected && 'sign' in selected) {
        try { signed = await selected.sign(password, toSign); }
        catch (cause) { notePasswordFailure(cause); }
      } else {
        let files: CertificateFiles;
        if (selected) files = await selected.getFiles();
        else if (manualMode && kind === 'PFX' && bundle) files = { kind, bundle };
        else if (manualMode && kind === 'NPKI' && certificate && privateKey) files = { kind, certificate, privateKey, chain };
        else throw new Error('목록에서 인증서를 선택하거나 인증서 파일을 직접 선택해 주세요.');
        try { signed = await signWithCertificateFiles(files, password, toSign); }
        catch (cause) { notePasswordFailure(cause); }
      }
      window.localStorage.removeItem(passwordLockKey); setPasswordFailures(0); setLockedUntil(0);
      await onProof(signed);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '인증서 서명에 실패했습니다.');
    } finally {
      setSigning(false); setPassword(''); setKeyboardOpen(false); setBundle(undefined); setCertificate(undefined);
      setPrivateKey(undefined); setChain(undefined);
      form.querySelectorAll<HTMLInputElement>('input[type="file"]').forEach(input => { input.value = ''; });
    }
  }

  function cancel() {
    setPassword(''); setKeyboardOpen(false); setSelectedId(undefined); setError('');
    if (onCancel) { onCancel(); return; }
    if (window.opener && !window.opener.closed) window.close();
    else if (window.history.length > 1) window.history.back();
    else window.location.hash = '#/login';
  }

  return <section className="account-proof cert-dialog" aria-label={actionLabel ?? '공동인증서 선택 및 로그인'}>
    <div className="cert-dialog-titlebar">
      <strong>공동인증서 인증</strong>
      <span>v{appPackage.version}</span>
      <button type="button" aria-label="인증서 선택 취소" onClick={cancel}><X size={19} /></button>
    </div>
    <div className="cert-dialog-content">
    <div className="cert-brand-banner">
      <img src="/inviz-logo-transparent.png" alt="INVIZ" />
    </div>
    <p className="cert-dialog-instruction">인증서 저장위치를 선택해 주세요.</p>
    <p role="status" className="cert-dialog-timer">{expired ? '인증 요청이 만료되었습니다. 새 요청을 시작해 주세요.' : `남은 시간 ${Math.ceil((expires - now) / 1000)}초`}</p>
    {expiryWarning && <p role="alert">선택한 공동인증서가 30일 이내에 만료됩니다. 만료일: {selected.expiresAt!.toLocaleDateString('ko-KR')}</p>}
    {passwordLocked && <p role="alert">비밀번호 입력이 잠겼습니다. {Math.ceil((lockedUntil - now) / 60000)}분 뒤 다시 시도해 주세요.</p>}
    {bridgeState === 'unavailable' && <div className="cert-bridge-setup" role="status">
      <strong>이 PC의 인증서 프로그램에 연결할 수 없습니다</strong>
      <p>처음 사용하는 PC라면 설치 파일의 압축을 풀고 <code>Install.cmd</code>를 실행하세요. 이미 설치했다면 브라우저의 로컬 네트워크 접근을 허용한 뒤 다시 검색하세요.</p>
      {import.meta.env.VITE_CERT_INSTALLER_URL && <>
        <a href={import.meta.env.VITE_CERT_INSTALLER_URL} target="_blank" rel="noopener noreferrer">Windows 인증서 프로그램 다운로드</a>
        <small>새 창에서 다운로드가 시작되지 않으면 주소를 복사해 브라우저 주소창에 붙여넣으세요: <code>{import.meta.env.VITE_CERT_INSTALLER_URL}</code></small>
      </>}
      <button type="button" onClick={() => { setError(''); setScanRetry(value => value + 1); }}>설치 후 다시 검색</button>
      <small>인증서는 현재 사용 중인 PC에서만 검색됩니다. 브라우저가 막은 시스템 폴더를 강제로 열 수는 없습니다.</small>
    </div>}
    <form onSubmit={submit}>
      <fieldset disabled={busy || signing || expired} className="enroll-form-fields">
        <legend className="enroll-sr-only">공동인증서</legend>
        <div className="cert-media" aria-label="인증서 저장매체">
          {MEDIA.map(item => <button key={item} type="button" aria-pressed={medium === item}
            onClick={() => { setMedium(item); setSelectedId(undefined); setManualMode(false); setError(''); }}>
            {item === 'hard-disk' ? <HardDrive aria-hidden="true" size={28} /> : <Usb aria-hidden="true" size={28} />}
            <span>{MEDIA_LABEL[item]}</span>
          </button>)}
        </div>
        <p className="cert-dialog-instruction cert-list-heading">인증서를 선택해 주세요.</p>
        <div className="cert-list-wrap">
          <table className="cert-list">
            <thead><tr><th>구분</th><th>사용자 / 인증서</th><th>만료기간</th><th>발급자</th></tr></thead>
            <tbody>{entries.map(entry => {
              const isExpired = Boolean(entry.expiresAt && entry.expiresAt.getTime() <= now);
              return <tr key={entry.id} className={isExpired ? 'expired' : selectedId === entry.id ? 'selected' : ''} onClick={() => {
                if (!isExpired) { setSelectedId(entry.id); setManualMode(false); }
              }}>
                <td><label className="cert-row-select"><input type="radio" name="local-certificate" aria-label={`${entry.name} 인증서 선택`} checked={selectedId === entry.id}
                  disabled={isExpired} onChange={() => { setSelectedId(entry.id); setManualMode(false); }} />
                  {entry.kind === 'NPKI' ? '공동' : 'PFX/P12'}</label></td>
                <td><strong>{entry.name}</strong><small title={entry.path}>{entry.path || '선택한 폴더'}</small></td>
                <td>{entry.expiresAt ? entry.expiresAt.toLocaleDateString('ko-KR') : '확인 전'}{isExpired ? ' (만료)' : ''}</td>
                <td>{entry.issuer}</td>
              </tr>;
            })}</tbody>
          </table>
          {scanning && <p aria-live="polite">허용된 저장위치에서 인증서를 검색 중입니다…</p>}
          {!scanning && entries.length === 0 && <p className="cert-empty">{bridgeState === 'unavailable'
            ? '이 PC의 인증서 프로그램에 연결되지 않았습니다. 아래 안내에 따라 연결을 확인해 주세요.'
            : permissionNeeded[medium] ? '저장된 폴더의 접근을 다시 허용해 주세요.' :
            directories[medium] ? '선택한 폴더와 하위 폴더에서 인증서를 찾지 못했습니다.' :
            bridgeState === 'available' ? `${medium === 'hard-disk' ? '하드디스크' : '이동식디스크'}에서 인증서를 찾지 못했습니다. 저장매체 연결과 인증서 위치를 확인해 주세요.`
              : '처음 사용할 때 검색할 PC 폴더를 한 번 선택해 주세요.'}</p>}
        </div>
        {scanLimited[medium] && <p className="cert-scan-limit" role="alert">검색 범위가 너무 넓어 일부 폴더만 확인했습니다. NPKI 상위 폴더를 직접 선택해 다시 검색해 주세요.</p>}
        <div className="cert-location-actions">
          <button type="button" onClick={() => void chooseDirectory(false)} disabled={signing}><FolderSearch size={17} /> 인증서 찾기</button>
          {permissionNeeded[medium] && <button type="button" onClick={() => void chooseDirectory(true)} disabled={signing}>저장된 폴더 접근 허용</button>}
          {directories[medium] && !permissionNeeded[medium] && <button type="button" onClick={() => void chooseDirectory(true)} disabled={signing}>목록 새로고침</button>}
        </div>
        <p className="cert-location-help">인비즈 PC 인증서 프로그램이 실행 중이면 NPKI 인증서를 자동으로 표시합니다. 연결되지 않으면 폴더를 선택해 검색할 수 있습니다.</p>
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
        <label className="cert-password-label">현재 인증서 암호를 입력해 주세요.<span className="cert-password-input"><input ref={passwordInput} type="password" aria-label="인증서 비밀번호" autoComplete="off" value={password}
          onChange={event => { virtualCaret.current = null; setPassword(event.target.value); }}
          placeholder={certificateChosen ? '인증서 비밀번호' : '먼저 위에서 인증서를 선택해 주세요'} disabled={!certificateChosen} required />
          <button type="button" className="cert-keyboard-toggle" disabled={!certificateChosen} aria-label={keyboardOpen ? '가상키보드 닫기' : '가상키보드 열기'}
            aria-expanded={keyboardOpen} aria-controls="cert-virtual-keyboard" onClick={() => setKeyboardOpen(!keyboardOpen)}>
            <Keyboard aria-hidden="true" size={21} />
          </button></span></label>
        {keyboardOpen && certificateChosen && <CertificateVirtualKeyboard onInsert={insertPassword} onBackspace={deletePasswordCharacter}
          onClose={() => setKeyboardOpen(false)} />}
        <div className="cert-dialog-buttons">
          <button className="enroll-primary" disabled={!password || !certificateChosen || signing || passwordLocked}>
            {signing ? '인증서 확인 중…' : actionLabel ?? '확인'}
          </button>
          <button type="button" className="cert-dialog-cancel" onClick={cancel}>취소</button>
        </div>
      </fieldset>
    </form>
    {error && <p role="alert">{error}</p>}
    </div>
  </section>;
}
