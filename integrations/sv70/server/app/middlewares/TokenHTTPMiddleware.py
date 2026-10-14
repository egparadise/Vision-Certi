"""
토큰 인증 미들웨어 (순수 ASGI)

- BaseHTTPMiddleware 제거 → 이벤트 루프 병목 해소
- JWT 토큰 검증 및 사용자 정보 주입
- 이벤트 로깅 (비동기 버퍼)
- 캐시 헤더 제어
"""
import re
import time
import asyncio
import threading
from datetime import datetime
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.database import manageDB
from app.libs import exception_handler, event_logger
from app.crud import get_user
from app.core import EXCEPT_PATH_REGEX, EXCEPT_PATH_LIST
from app.errors import NotAuthorized

# 토큰 캐시: { token_key: { "data": user_data, "expires": timestamp } }
_token_cache: dict = {}
_token_cache_lock = threading.Lock()
_TOKEN_CACHE_TTL = 300  # 5분
_TOKEN_CACHE_MAX_SIZE = 2000


def _get_cached_user(token_key: str):
    """캐시에서 사용자 조회. 만료/미존재 시 None 반환."""
    with _token_cache_lock:
        entry = _token_cache.get(token_key)
        if entry and entry["expires"] > time.time():
            return entry["data"]
        _token_cache.pop(token_key, None)
        return None


def _set_cached_user(token_key: str, user_data):
    """캐시에 사용자 저장."""
    with _token_cache_lock:
        if len(_token_cache) >= _TOKEN_CACHE_MAX_SIZE:
            oldest_key = min(_token_cache, key=lambda k: _token_cache[k]["expires"])
            _token_cache.pop(oldest_key, None)
        _token_cache[token_key] = {
            "data": user_data,
            "expires": time.time() + _TOKEN_CACHE_TTL,
        }


def invalidate_token_cache(token_key: str = None):
    """토큰 캐시 무효화. token_key=None이면 전체 초기화."""
    with _token_cache_lock:
        if token_key is None:
            _token_cache.clear()
        else:
            _token_cache.pop(token_key, None)


async def url_pattern_check(path, pattern):
    result = re.match(pattern, path)
    if result:
        return True
    return False


# 불변 DICOM 리소스 경로 (브라우저 캐시 허용)
_CACHEABLE_PATHS = ("/frames/", "/metadata", "/thumbnail", "/rendered")

# no-cache 헤더 (바이트 튜플)
_NO_CACHE_HEADERS = [
    (b"cache-control", b"no-cache, no-store, must-revalidate"),
    (b"pragma", b"no-cache"),
    (b"expires", b"0"),
]

# 필터링 대상 헤더 이름
_CACHE_HEADER_NAMES = {b"cache-control", b"pragma", b"expires"}


class _CapturedResponse:
    """event_logger에 전달하기 위한 경량 응답 객체"""
    __slots__ = ('status_code',)

    def __init__(self, status_code: int):
        self.status_code = status_code


class TokenASGIMiddleware:
    """순수 ASGI 토큰 인증 미들웨어
    BaseHTTPMiddleware를 제거하여 이벤트 루프 병목 해소:
    - 내부 asyncio Task 생성 없음
    - 응답 바디 메모리 파이핑 없음
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        # state 초기화
        if "state" not in scope:
            scope["state"] = {}

        scope["state"]["req_time"] = datetime.now()
        scope["state"]["start"] = time.time()
        scope["state"]["inspect"] = None
        scope["state"]["user"] = None

        # Request 객체 (헤더/client 접근용, body 소비 없음)
        request = Request(scope, receive)

        # IP 추출
        ip = request.headers.get(
            "x-forwarded-for",
            request.client.host if request.client else "unknown",
        )
        scope["state"]["ip"] = ip.split(",")[0] if "," in ip else ip

        url = scope.get("path", "")
        error = None

        # ── 토큰 검증 ──
        try:
            if await url_pattern_check(url, EXCEPT_PATH_REGEX) or url in EXCEPT_PATH_LIST:
                pass  # 인증 제외 경로
            elif url.startswith("/api") or url.startswith("/token"):
                headers = request.headers
                if 'Authorization' not in headers:
                    raise NotAuthorized()
                token_key = headers.get("Authorization").replace("Bearer ", "")

                # 캐시 우선 조회 — Vision-Certi 인증서 세션(vc1.)은 해지 즉시 반영되도록 캐시하지 않는다.
                is_vc_token = token_key.startswith("vc1.")
                cached = None if is_vc_token else _get_cached_user(token_key)
                if cached is not None:
                    scope["state"]["user"] = cached
                else:
                    def _sync_get_user(tk):
                        s = None
                        try:
                            s = next(manageDB.session())
                            return get_user(tk, s)
                        finally:
                            if s:
                                s.close()

                    scope["state"]["user"] = await asyncio.to_thread(
                        _sync_get_user, token_key
                    )
                    if not is_vc_token:
                        _set_cached_user(token_key, scope["state"]["user"])
        except Exception as e:
            error = await exception_handler(e)
            error_dict = dict(
                status=error.status_code, msg=error.msg,
                detail=error.detail, code=error.code,
            )
            response = JSONResponse(
                status_code=error.status_code, content=error_dict
            )
            await response(scope, receive, send)
            await event_logger(
                request=request, response=response,
                error=error, request_body=None,
            )
            return

        # ── 내부 앱 실행 (캐시 헤더 제어 + 상태 캡처) ──
        is_cacheable = any(p in url for p in _CACHEABLE_PATHS)
        captured_status = 200

        async def send_wrapper(message):
            nonlocal captured_status
            if message["type"] == "http.response.start":
                captured_status = message.get("status", 200)

                if not is_cacheable:
                    # 기존 Cache-Control/Pragma/Expires 제거 후 no-cache 추가
                    raw_headers = [
                        (k, v)
                        for k, v in message.get("headers", [])
                        if k not in _CACHE_HEADER_NAMES
                    ]
                    raw_headers.extend(_NO_CACHE_HEADERS)
                    message = {
                        "type": "http.response.start",
                        "status": captured_status,
                        "headers": raw_headers,
                    }
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as e:
            error = await exception_handler(e)
            error_dict = dict(
                status=error.status_code, msg=error.msg,
                detail=error.detail, code=error.code,
            )
            response = JSONResponse(
                status_code=error.status_code, content=error_dict
            )
            await response(scope, receive, send)
            await event_logger(
                request=request, response=response,
                error=error, request_body=None,
            )
            return

        # ── 이벤트 로깅 (응답 완료 후, 비동기 버퍼) ──
        await event_logger(
            request=request,
            response=_CapturedResponse(captured_status),
            error=error,
            request_body=None,
        )
