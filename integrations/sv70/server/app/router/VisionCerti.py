"""SV70 Vision-Certi integration routes (/api/vc/*). Logic: app/integrations/vision_certi (docs/VISION_CERTI_INTEGRATION.md)."""
from app.integrations.vision_certi.api import build_router
from app.integrations.vision_certi.runtime import api_deps

router = build_router(api_deps())
