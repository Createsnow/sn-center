from app.schemas.common import In
from app.schemas.rule import LineRef


class PreviewIn(In):
    """范围：BILLS 整单（按勾选顺序，单据内按序号）/ LINES 按物料行（按点选顺序）；只能在同一张 PI 内。"""

    pi_no: str | None = None
    scope: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
    #: 不填 = 默认（额度与单次默认上限取小）
    qty: int | None = None


class RunIn(In):
    token: str | None = None


class RevokeIn(In):
    reason: str | None = None


class AllocateIn(In):
    """分配范围：整张 PI（都不填）/ 单据 / 物料行。"""

    pi_no: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
