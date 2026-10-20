from app.schemas.common import In
from app.schemas.rule import LineRef


class PreviewIn(In):
    """范围：BILLS 整单（按勾选顺序，单据内按序号）/ LINES 按物料行（按点选顺序）；只能在同一张 PI 内。
    不填数量：范围内每个还有额度的物料行整行生成。"""

    pi_no: str | None = None
    scope: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None


class RunIn(In):
    token: str | None = None


class RevokeIn(In):
    """给了单据和序号 = 只撤这一个物料行；都不给 = 撤这次生成中剩下的全部物料行。"""

    reason: str | None = None
    bill_no: str | None = None
    seq: int | None = None


class AllocateIn(In):
    """分配范围：整张 PI（都不填）/ 单据 / 物料行。"""

    pi_no: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
