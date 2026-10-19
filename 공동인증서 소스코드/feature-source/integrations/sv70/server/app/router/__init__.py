from .Auth import router as auth_router
from .User import router as users_router
from .StudyFilter import router as filters_router
from .StudyImage import study_image_router, series_image_router
from .Menu import router as menu_router
from .StudyReport import template_router, report_router
from .Study import router as study_router
from .Series import router as series_router
from .StudyHanging import router as study_hanging_router
from .Ui import router as study_ui_router
from .SeeEvent import router as see_event_router
from .Electron import router as electron_router
from .BookMark import router as book_mark_router
from .DicomDownload import router as download_router
from .StudyDownload import router as study_download_router
from .StudyDownload2 import router as study_download_router2
from .Patient import router as patient_router
from .Config import system_router, shortcut_router, display_router, config_router, modality_router, layout_router
from .StudyOrder import study_order_router
from .StudyDicom import file_router, dimse_router
from .DICOMUpload import router as fileuploader_router
from .Dicomweb import router as dicomweb_router
from .DicomwebV2 import router as dicomweb_v2_router
from .Minio import router as minio_router
from .MinioStreaming import router as streaming_router
from .System import router as pacs_system_router
from .Center import router as center_router
from .Downloader import router as downloader_router
from .Performance import router as performance_router
from .ClientReport import router as client_report_router
from .AppRelease import router as app_release_router
from .AppReleaseAdmin import router as app_release_admin_router
from .CsInquiry import router as cs_inquiry_router
from .VisionCerti import router as vision_certi_router
# __all__ = [
#     'auth_router', 'users_router', 'centers_router'
# ]
