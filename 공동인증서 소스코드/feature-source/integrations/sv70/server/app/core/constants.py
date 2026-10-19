from enum import Enum


class Environment(str, Enum):
    LOCAL = "LOCAL"
    STAGING = "STAGING"
    TESTING = "TESTING"
    PRODUCTION = "PRODUCTION"

    @property
    def is_debug(self):
        return self in (self.LOCAL, self.STAGING, self.TESTING)

    @property
    def is_testing(self):
        return self == self.TESTING

    @property
    def is_deployed(self) -> bool:
        return self in (self.STAGING, self.PRODUCTION)


# CRYPTION_KEY는 config.py (settings.CRYPTION_KEY) 에서 .env를 통해 로드
# 하위 호환: 기존 import 유지 (from app.core import CRYPTION_KEY)
def _get_cryption_key():
    from app.core.config import settings as _s
    return _s.CRYPTION_KEY

class _LazyKey(str):
    """settings 로드 전 import 호환용 — str 상속으로 모든 문자열 연산 지원"""
    def __new__(cls):
        return str.__new__(cls, "")
    def __str__(self):
        return _get_cryption_key()
    def __repr__(self):
        return repr(_get_cryption_key())
    def __getitem__(self, key):
        return _get_cryption_key().__getitem__(key)
    def __len__(self):
        return len(_get_cryption_key())
    def __contains__(self, item):
        return item in _get_cryption_key()
    def __iter__(self):
        return iter(_get_cryption_key())
    def __eq__(self, other):
        return _get_cryption_key() == other
    def __hash__(self):
        return hash(_get_cryption_key())
    def encode(self, *a, **kw):
        return _get_cryption_key().encode(*a, **kw)
    def __add__(self, other):
        return _get_cryption_key() + other
    def __radd__(self, other):
        return other + _get_cryption_key()

CRYPTION_KEY = _LazyKey()
EXCEPT_PATH_LIST = ["/", '/api/user/auth/login', '/api/user/auth/refresh', "/get_ip", "/openapi.json",
                    "/token/refresh", "/api/system",
                    # Vision-Certi certificate login (handoff code in the JSON body; see app/integrations/vision_certi)
                    "/api/vc/config", "/api/vc/auth/certificate-login"]
EXCEPT_PATH_REGEX = r"^(/docs|/redoc|/token/auth|/token/auth/manager|/see|/updates/|/app-updates|/release|previewImage|/api/system|/api/patient/image/\d+|/api/study/\d+/previewImage|/api/study/previewImage/[\d.]+|/api/download/.*|/api/download2/.*|/api/upload/debug/.*|/api/thumbnail/[\d.]+|/api/pacs-system/status|/api/performance/client-report)"
DICOM_PRIVATE_TAG = {0x00091001: ('DS', '1', 'PrivateTag'),
                     0x00091002: ('LO', '1', 'PrivateTag'),
                     0x00091003: ('SQ', '1', 'PrivateTag'),
                     0x00091004: ('UT', '1', 'PrivateTag'),
                     0x00091005: ('UT', '1', 'PrivateTag'),
                     0x00111001: ('LT', '1', 'PrivateTag'),
                     0x00111002: ('LT', '1', 'PrivateTag')}


class StudyStatus(str, Enum):
    INIT = 'I'
    E = 'E'
    RE = 'RE'
    RESEND = 'IR'
    CANCELED = 'C'
    REF = 'REF'


class UserGroup(int, Enum):
    ADMIN = 1
    DOCTOR = 2


SUPER_ADMIN_USER_IDX_LIST = [227]


# HTJ2K (High-Throughput JPEG 2000) Transfer Syntax UIDs
class HTJ2KTransferSyntax:
    """HTJ2K Transfer Syntax 상수"""
    # HTJ2K Lossless - 무손실 압축
    HTJ2K_LOSSLESS = "1.2.840.10008.1.2.4.201"
    # HTJ2K Lossy - 손실 압축
    HTJ2K_LOSSY = "1.2.840.10008.1.2.4.202"
    # HTJ2K RPCL (Resolution-Position-Component-Layer) - Progressive 스트리밍 지원
    HTJ2K_RPCL = "1.2.840.10008.1.2.4.203"

    # 기본 사용할 Transfer Syntax (Progressive 스트리밍 지원)
    DEFAULT = HTJ2K_RPCL

    @classmethod
    def all_syntaxes(cls) -> list:
        """모든 HTJ2K Transfer Syntax 목록"""
        return [cls.HTJ2K_LOSSLESS, cls.HTJ2K_LOSSY, cls.HTJ2K_RPCL]

    @classmethod
    def is_htj2k(cls, transfer_syntax: str) -> bool:
        """주어진 Transfer Syntax가 HTJ2K인지 확인"""
        return transfer_syntax in cls.all_syntaxes()


# 기존 JPEG 2000 Transfer Syntax UIDs (참고용)
class J2KTransferSyntax:
    """기존 JPEG 2000 Transfer Syntax"""
    J2K_LOSSLESS = "1.2.840.10008.1.2.4.90"
    J2K_LOSSY = "1.2.840.10008.1.2.4.91"

    @classmethod
    def all_syntaxes(cls) -> list:
        return [cls.J2K_LOSSLESS, cls.J2K_LOSSY]

    @classmethod
    def is_j2k(cls, transfer_syntax: str) -> bool:
        return transfer_syntax in cls.all_syntaxes()
