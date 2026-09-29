from fastapi import APIRouter

from app.api.routes import (
    acquire,
    audits,
    auth,
    dashboard,
    factories,
    generate,
    health,
    open,
    orders,
    rules,
    sn,
    transfers,
    users,
)

api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(users.router)
api_router.include_router(factories.router)
api_router.include_router(orders.router)
api_router.include_router(rules.router)
api_router.include_router(generate.router)
api_router.include_router(acquire.router)
api_router.include_router(open.router)
api_router.include_router(transfers.router)
api_router.include_router(sn.router)
api_router.include_router(audits.router)
api_router.include_router(dashboard.router)

__all__ = ["api_router", "health"]
