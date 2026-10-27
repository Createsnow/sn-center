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


class RevokeConfigIn(In):
    """撤销所选物料行的配置（还没有号的行）。"""

    pi_no: str | None = None
    lines: list[LineRef] | None = None


class RevokeLinesIn(In):
    """撤到所选物料行为止：从 PI 流水末尾往前撤，流水排在所选行之后的物料行一并撤销。"""

    pi_no: str | None = None
    lines: list[LineRef] | None = None
    reason: str | None = None
