"""SV70 PACS <-> Vision-Certi integration (certificate login handoff and certificate-signed report approval).

Pure package: nothing here imports ``app.core`` or ``app.database`` at import time, so it can be tested without the
legacy settings (.env), MySQL, MinIO or SMS. The FastAPI wiring for the running server is ``app/router/VisionCerti.py``.
See docs/VISION_CERTI_INTEGRATION.md.
"""
