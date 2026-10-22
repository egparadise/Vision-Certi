/** This page stays open after delivery. PACS owns its subsequent login/navigation. */
export function CertificateComplete() {
  return <section className="certificate-complete" role="status" aria-live="polite"
    style={{ maxWidth: 560, margin: '64px auto', padding: '40px 24px', textAlign: 'center',
      background: '#fff', border: '1px solid #dce5ef', borderRadius: 16, color: '#153153' }}>
    <div aria-hidden="true" style={{ color: '#00856a', fontSize: 56 }}>✓</div>
    <h1 style={{ fontSize: 28, margin: '16px 0' }}>인증되었습니다.</h1>
    <p>공동인증서 확인 결과를 요청한 PACS에 전달했습니다.</p>
    <p>PACS 화면에서 계속 진행해 주세요. 이 창은 오른쪽 위 X로 닫을 수 있습니다.</p>
    <button type="button" onClick={() => { if (window.opener && !window.opener.closed) window.opener.focus(); }}
      style={{ marginTop: 16, padding: '12px 24px', color: '#fff', background: '#263eaa', border: 0, borderRadius: 6 }}>
      PACS 화면으로 돌아가기
    </button>
  </section>;
}
