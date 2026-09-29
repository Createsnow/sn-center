from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.api.deps import run_sync
from app.db.session import db

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def live() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/health/ready")
@router.get("/api/health")
async def ready():
    try:
        await run_sync(db.scalar, "SELECT 1")
    except Exception:  # noqa: BLE001
        return JSONResponse({"status": "db_unavailable"}, status_code=503)
    return {"status": "ok", "db": "mysql"}
