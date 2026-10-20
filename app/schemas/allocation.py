from app.schemas.common import In
from app.schemas.rule import LineRef


class AllocateIn(In):
    """分配范围：整张 PI（都不填）/ 单据 / 物料行。"""

    pi_no: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
