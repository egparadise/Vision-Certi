from fastapi import FastAPI
from fastapi.security import APIKeyHeader
import uvicorn
import sqlalchemy

from starlette.middleware.cors import CORSMiddleware
from starlette.middleware.gzip import GZipMiddleware
from starlette.staticfiles import StaticFiles
from typing import AsyncGenerator
from contextlib import asynccontextmanager
from datetime import datetime
import os
import asyncio

# 로컬 임포트
from app.middlewares import TrustedHostMiddleware, TokenASGIMiddleware
from app.libs import ensure_directories_exist
from app.database.DataBase import db, manageDB
from app.router import (
    auth_router, users_router, filters_router,
     menu_router, report_router,
    study_router, series_router, study_hanging_router,
    study_ui_router, see_event_router, electron_router,
    book_mark_router, download_router, patient_router,
    config_router, system_router, shortcut_router, display_router,
    modality_router, layout_router, study_order_router,
    study_image_router, series_image_router, template_router,
    file_router, dimse_router, study_download_router, fileuploader_router,
    dicomweb_router, dicomweb_v2_router, study_download_router2, minio_router, streaming_router,
    pacs_system_router, center_router, downloader_router,
    performance_router, client_report_router, app_release_router,
    app_release_admin_router, cs_inquiry_router, vision_certi_router
)
from app.core import settings, app_configs
from app.libs.Logger import Logger
from app.utils.prior_study_scheduler import start_prior_study_scheduler
from app.utils.image_extend_filler import start_image_extend_filler
from app.utils.dicom_preprocess_generator import start_dicom_preprocess_generator
from app.utils.system_monitor import start_system_monitor
from app.middlewares.PerformanceMiddleware import PerformanceASGIMiddleware, perf_collector
from app.libs.Event_logger import event_log_buffer

# 로거 초기화
logger = Logger(
    os.path.join(settings.BASE_URL, 'logs'),
    'app_data.log',
    'app_data'
).initLogger()

# API 키 헤더 설정
API_KEY_HEADER = APIKeyHeader(name="Authorization", auto_error=False)

# 필요한 디렉토리 구조 생성
REQUIRED_DIRECTORIES = [
    settings.RELEASE_PATH,
    settings.BASE_DATA_URL,
    settings.BASE_SPOOL_URL,
    settings.BASE_DICOM_URL,
    settings.BASE_FILES_URL,
    settings.BASE_DICOM_EXCEPTION_URL,
    settings.BASE_DICOM_EXIT_URL
]


class ApplicationError(Exception):
    """애플리케이션 레벨 예외의 기본 클래스"""
    pass


class ResourceInitializationError(ApplicationError):
    """리소스 초기화 실패 시 발생하는 예외"""
    pass


def create_db_url(prefix: str = "") -> sqlalchemy.engine.URL:
    """
    데이터베이스 연결 URL 생성
    Args:  prefix (str): 설정 접두사 (예: 'MANGE_' for manageDB)
    Returns: sqlalchemy.engine.URL: 구성된 데이터베이스 URL
    """
    url = sqlalchemy.engine.URL.create(
        drivername=getattr(settings, f"{prefix}DRIVER_NAME"),
        username=getattr(settings, f"{prefix}DB_USER"),
        password=getattr(settings, f"{prefix}DB_PASSWD"),
        host=getattr(settings, f"{prefix}DB_HOST"),
        database=getattr(settings, f"{prefix}DB_NAME")
    )

    # MySQL 옵션 추가
    query = dict(url.query) if hasattr(url, 'query') and url.query else {}
    query.update({
        "charset": "utf8mb4",
        "sql_mode": "STRICT_TRANS_TABLES"
    })
    url = url.set(query=query) if hasattr(url, 'set') else url

    return url


async def init_databases() -> None:
    """
    데이터베이스 초기화 및 연결 설정
    Raises:  ResourceInitializationError: 데이터베이스 초기화 실패 시
    """
    try:
        # 메인 데이터베이스 초기화
        await db.init_app(
            DB_URL=create_db_url(),
            logger=logger,
            DB_POOL_RECYCLE=900,
            DB_ECHO=settings.DB_ECHO,
            DB_PARAMETERS=settings.DB_PARAMETERS,
            DB_POOL_SIZE=15,
            DB_MAX_OVERFLOW=5,
            DB_POOL_TIMEOUT=30,
            app=app
        )

        # 관리 데이터베이스 초기화
        await manageDB.init_app(
            DB_URL=create_db_url('MANGE_'),
            logger=logger,
            DB_POOL_RECYCLE=900,
            DB_ECHO=settings.DB_ECHO,
            DB_PARAMETERS=settings.DB_PARAMETERS,
            DB_POOL_SIZE=10,
            DB_MAX_OVERFLOW=5,
            DB_POOL_TIMEOUT=30,
            app=app
        )

        logger.info("Database initialization completed successfully")

    except Exception as e:
        logger.error(f"Database initialization failed: {str(e)}")
        raise ResourceInitializationError(
            f"Failed to initialize databases: {str(e)}")


async def init_directories() -> None:
    """
    필요한 디렉토리 구조 초기화
    Raises: ResourceInitializationError: 디렉토리 생성 실패 시
    """
    try:
        ensure_directories_exist(*REQUIRED_DIRECTORIES)
        logger.info("Directory structure initialized successfully")
    except Exception as e:
        logger.error(f"Failed to create directories: {str(e)}")
        raise ResourceInitializationError(
            f"Directory initialization failed: {str(e)}")


# 새로 추가: DB 연결 모니터링 태스크
async def db_health_monitor():
    """주기적으로 데이터베이스 연결 상태 모니터링 및 관리"""
    while True:
        try:
            # 데이터베이스 연결 상태 확인 및 복구
            main_db_status = await db.ensure_connected()
            manage_db_status = await manageDB.ensure_connected()

            # 상태 로깅 - 문제가 있을 때만 로깅
            if not (main_db_status and manage_db_status):
                logger.warning(
                    f"Database connection status: main={main_db_status}, manage={manage_db_status}")

            # pool_pre_ping=True 로 자동 복구되므로 수동 dispose 불필요
            await asyncio.sleep(180)

        except Exception as e:
            logger.error(f"DB health monitor error: {str(e)}")
            await asyncio.sleep(60)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator:
    """
    애플리케이션 라이프사이클 관리
    Args:  app: FastAPI 애플리케이션 인스턴스
    Yields: None
    """
    logger.info("Starting application initialization...")

    try:
        # asyncio 기본 스레드풀 확대 (동시 DICOM I/O 병목 방지)
        import concurrent.futures
        loop = asyncio.get_event_loop()
        loop.set_default_executor(concurrent.futures.ThreadPoolExecutor(max_workers=64))

        # 초기화 단계
        await init_directories()
        await init_databases()
        await db.connect()
        await manageDB.connect()

        # MinIO 클라이언트 초기화
        from app.utils.minio_manager import minio_manager
        await minio_manager.initialize()

        # ImageExtend 자동 채우기 시작
        image_extend_task = start_image_extend_filler()

        # 새로 추가: 연결 모니터링 태스크 시작
        monitor_task = asyncio.create_task(db_health_monitor())

        # 이전검사 요청 완료 감지 스케줄러 시작 (단일 워커)
        prior_study_task = start_prior_study_scheduler()

        # DICOM 전처리 통합 생성기 (metadata + rendered)
        preprocess_task = start_dicom_preprocess_generator()

        # 성능 모니터링 시작
        system_monitor_task = await start_system_monitor()
        perf_collector.start_flush_loop()

        # 이벤트 로그 배치 flush 시작
        event_log_buffer.start_flush_loop()

        # 프레임 캐시: 배치 변환기에서 변환 시 직접 로컬+Redis 캐시 저장

        logger.info("Application successfully initialized")
        yield
    except Exception as e:
        logger.error(f"Application startup failed: {str(e)}")
        raise

    finally:
        # 종료 단계
        logger.info("Starting graceful shutdown...")
        try:
            # 새로 추가: 모니터링 태스크 정리
            if 'monitor_task' in locals():
                monitor_task.cancel()
                try:
                    await monitor_task
                except asyncio.CancelledError:
                    pass

            # 이전검사 요청 스케줄러 정리
            if 'prior_study_task' in locals() and prior_study_task:
                prior_study_task.cancel()
                try:
                    await prior_study_task
                except asyncio.CancelledError:
                    pass

            if 'image_extend_task' in locals() and image_extend_task:
                image_extend_task.cancel()
                try:
                    await image_extend_task
                except asyncio.CancelledError:
                    pass

            # 성능 모니터링 정리
            if 'system_monitor_task' in locals() and system_monitor_task:
                from app.utils.system_monitor import system_monitor
                system_monitor.stop()
                try:
                    await system_monitor_task
                except asyncio.CancelledError:
                    pass
            perf_collector.stop_flush_loop()
            await event_log_buffer.stop_flush_loop()


            await db.disconnect()
            await manageDB.disconnect()

            # MinIO 클라이언트 정리
            from app.utils.minio_manager import minio_manager
            await minio_manager.close()

            logger.info("Graceful shutdown completed")
        except Exception as e:
            logger.error(f"Error during shutdown: {str(e)}")

def setup_middlewares(app: FastAPI) -> None:
    """미들웨어 설정
    Starlette 미들웨어는 LIFO 순서:
    실행 순서: TrustedHost → CORS → Performance(시간측정) → Token(인증) → 핸들러
    Performance는 call_next() 반환 후 request.state.user 접근 가능
    """
    # 1. Token 체크 (가장 안쪽, 핸들러 직전) — 순수 ASGI 미들웨어
    app.add_middleware(TokenASGIMiddleware)
    # 2. 성능 모니터링 (Token을 감싸서 전체 시간 측정) — 순수 ASGI 미들웨어
    app.add_middleware(PerformanceASGIMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.ALLOW_SITE,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
        # preflight(OPTIONS) 캐시 상향: 미지정 시 starlette 기본 600초 + URL 단위 캐시라,
        # Authorization 헤더 때문에 전부 preflight 대상인 webview XHR 이 시리즈별 고유 URL
        # (/series/{uid}/metadata 등)마다 OPTIONS 왕복(WAN RTT)을 선행했다. 7200s = Chromium 상한.
        max_age=7200,
    )
    app.add_middleware(
        TrustedHostMiddleware,
        allowed_hosts=settings.TRUSTED_HOSTS
    )
    # 5. GZip 압축 (가장 바깥, 500B 이상 응답 자동 압축)
    # compresslevel 미지정 시 기본 9(최고압축) — 레벨 6 대비 CPU 2~3배에 크기 이득 ~1%뿐이고,
    # 이 압축은 ASGI send 체인(이벤트 루프)에서 동기 실행된다(DicomwebV2 의 "gzip 이 루프를
    # 수백 ms 블록" 실증과 같은 지점). 대형 JSON(/series/viewer ~0.8MB, 워크리스트 폴링)의
    # 루프 블록을 줄인다. 완전 해결(스레드 오프로드)은 v2 metadata 처럼 라우트별 과제.
    app.add_middleware(GZipMiddleware, minimum_size=500, compresslevel=6)


def register_routers(app: FastAPI) -> None:
    """라우터 등록"""
    ROUTERS = [
        (auth_router, "/api", ["auth"]),
        (streaming_router, "/api", ["streaming_router"]),
        (minio_router, "/api", ["minio_router"]),
        (dicomweb_router, "/api", ["dicomweb"]),
        (dicomweb_v2_router, "/api", ["dicomweb-v2"]),
        (fileuploader_router, "/api", ["fileuploader"]),
        (study_order_router, "/api", ["study order"]),
        (modality_router, "/api", ["config"]),
        (layout_router, "/api", ["config"]),
        (config_router, "/api", ["config"]),
        (system_router, "/api", ["config"]),
        (shortcut_router, "/api", ["config"]),
        (display_router, "/api", ["config"]),
        (patient_router, "/api", ["patient"]),
        (study_download_router, "/api", ["download2"]),
        (study_download_router2, "/api", ["download3"]),
        (download_router, "/api", ["download"]),
        (study_ui_router, "/api", ["ui"]),
        (users_router, "/api", ["users"]),
        (study_hanging_router, "/api", ["study_hanging"]),
        (study_router, "/api", ["study"]),
        (series_router, "/api", ["series"]),
        (filters_router, "/api", ["filters"]),
        (report_router, "/api", ["report"]),
        (template_router, "/api", ["report"]),
        (file_router, "/api", ["DICOM"]),
        (dimse_router, "/api", ["DICOM DIMSE"]),
        (study_image_router, "/api", ["image"]),
        (series_image_router, "/api", ["image"]),
        (menu_router, "/api", ["menu"]),
        (see_event_router, "/see", ["See API"]),
        (electron_router, "/updates", ["electron API"]),
        (book_mark_router, "/api", ["bookMark API"]),
        (pacs_system_router, "/api", ["pacs-system"]),
        (center_router, "/api", ["center"]),
        (downloader_router, "/api", ["downloader"]),
        (performance_router, "/api", ["performance"]),
        (client_report_router, "/api", ["client-performance"]),
        (app_release_router, "/app-updates", ["app-update"]),
        (app_release_admin_router, "/api/app-release", ["app-update"]),
        (cs_inquiry_router, "/api", ["cs-inquiry"]),
        (vision_certi_router, "/api", ["vision-certi"])
    ]

    for router, prefix, tags in ROUTERS:
        app.include_router(router, prefix=prefix, tags=tags)


def create_app() -> FastAPI:
    """
    FastAPI 애플리케이션 팩토리
    Returns:  FastAPI: 구성된 FastAPI 애플리케이션 인스턴스
    """
    app = FastAPI(**app_configs, lifespan=lifespan)

    # 정적 파일 설정
    app.mount(
        "/release",
        StaticFiles(directory=settings.RELEASE_PATH),
        name="release"
    )

    # 미들웨어 및 라우터 설정
    setup_middlewares(app)
    register_routers(app)

    return app


# 애플리케이션 인스턴스 생성
app = create_app()

if __name__ == '__main__':
    # config = Config()
    # config.bind = ["127.0.0.1:8012"]
    # config.alpn_protocols = ["h2", "http/1.1"]
    # config.h2_max_concurrent_streams = 16777216
    # config.h2_max_concurrent_streams = 255
    # config.keep_alive_timeout = 5
    # config.graceful_timeout = 30
    # config.max_requests = 10000
    # config.max_requests_jitter = 1000
    # config.use_reloader = True
    #
    # asyncio.run(serve(app, config))
    uvicorn_config = {
        "host": '127.0.0.1',
        "port": 8014,
        "reload": True,
        "proxy_headers": True,
    }

    uvicorn.run("main:app", **uvicorn_config)
