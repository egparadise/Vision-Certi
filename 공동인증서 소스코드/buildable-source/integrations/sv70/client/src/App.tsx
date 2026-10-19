import {lazy, useEffect} from "react";
import {Route, Routes} from "react-router-dom";
import {ErrorBoundary} from "react-error-boundary";
import ErrorFallBack from "@pages/error/ErrorFallBack";
import {withAuth, withRequiredAuth} from "@pages/protected/Auth";
// 핵심 컴포넌트는 직접 import (lazy 제거)
import Layout from "@layout/Layout";
import WorkListPage from "@pages/workList/WorkList";
import Updater from "@pages/updater/Updater";
import { initCacheRouting } from "@libs/cacheRouting";

// 비핵심 페이지만 lazy 로딩
// vnext 파일럿은 방향 전환(구 UI 원본+엔진 백포트)으로 폐기 — eager import 는 전 창
// (워크리스트/리포트/설정)이 Cornerstone 정적 그래프까지 파싱하게 만들었다(검토 C16, BOOT-1 기여).
// 라우트는 직접 진입 검증용으로만 lazy 유지.
const ViewerNextShell = lazy(() => import("./viewerNext/ViewerNextShell"));
const ViewerLayout = lazy(() => import("@layout/ViewerLayout"));
const ModalLayout = lazy(() => import("@layout/ModalLayout"));
const Login = lazy(() => import("@pages/user/Login"));
const CertificateEnrollment = lazy(() => import("@pages/user/CertificateEnrollment"));
const ViewerPage = lazy(() => import("@pages/viewer/Viewer"));
const ReportPage = lazy(() => import("@pages/report/Report"));
const PageNotFoundPage = lazy(() => import("@pages/error/PageNotFound"));
const GlobalSetting = lazy(() => import("@pages/setting/GlobalSetting"));

const LayoutPage = withRequiredAuth(Layout)
const ViewerLayoutPage = withRequiredAuth(ViewerLayout)
const ModalLayoutPage = withRequiredAuth(ModalLayout)
const LoginPage = withAuth({
  requireAuth: false,
})(Login)
function App() {
  // Phase 3B: 자식 listener 등록 후 window-ready 신호 → 메인 프로세스가 버퍼된 이벤트를 flush
  useEffect(() => {
    window.electron?.ipcRenderer?.send('window-ready')
    initCacheRouting()
  }, [])
  return (
    <ErrorBoundary FallbackComponent={ErrorFallBack} onReset={() => window.location.reload()}>
      <Routes>
        {/* Phase 5C: 부팅 스플래시 — 메인창 초기 진입(#/updater), 확인 후 /login 으로 진행. 무인증. */}
        <Route path="/updater" element={<Updater/>}/>
        <Route path="/login" element={<LoginPage/>}/>
        <Route path="/certificate-enrollment" element={<CertificateEnrollment/>}/>
        <Route element={<LayoutPage/>}>
          <Route path="/" element={<WorkListPage/>}/>
        </Route>
        <Route element={<ViewerLayoutPage/>}>
          <Route path="/viewer" element={<ViewerPage/>}/>
          <Route path="/viewer/:studyIdx" element={<ViewerPage/>}/>
        </Route>
        <Route element={<ModalLayoutPage/>}>
          <Route path="/report" element={<ReportPage/>}/>
          <Route path="/report/:studyIdx" element={<ReportPage/>}/>
        </Route>
        {/* 전면 재작성 P0 — strangler 진입(기존 /viewer 와 병행, 플래그/직접 라우트). 인증가드 없이 P0 검증. */}
        <Route path="/vnext" element={<ViewerNextShell/>}/>
        <Route path="/vnext/:studyIdx" element={<ViewerNextShell/>}/>
        <Route path="/setting" element={<GlobalSetting/>}/>
        <Route path="*" element={<PageNotFoundPage/>}/>
      </Routes>
    </ErrorBoundary>
  )
}

export default App;
