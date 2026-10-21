from fastapi import APIRouter

from app.api.routes import (
    acquire,
    allocation,
    audits,
    auth,
    dashboard,
    delivery,
    factories,
    generate,
    health,
    mes,
    orders,
    rules,
    sn,
    sn_export,
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
api_router.include_router(allocation.router)
api_router.include_router(delivery.router)
api_router.include_router(sn_export.router)
api_router.include_router(acquire.router)
api_router.include_router(transfers.router)
api_router.include_router(sn.router)
api_router.include_router(audits.router)
api_router.include_router(dashboard.router)
api_router.include_router(mes.router)

__all__ = ["api_router", "health"]
