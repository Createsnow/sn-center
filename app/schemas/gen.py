from app.schemas.common import In
from app.schemas.rule import LineRef


class PreviewIn(In):
    """范围：BILLS 整单（按勾选顺序，单据内按序号）/ LINES 按物料行（按点选顺序）；只能在同一张 PI 内。
    不填数量：范围内每个还有额度的物料行整行生成。"""

    pi_no: str | None = None
    scope: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
    #: 流水来源：MAX 从最大号 + 1 起（默认）/ RANGE 手动起止流水号（十进制），用其间的空号段与未使用的号，
    #: 不够时从最大号之后接着编
    seq_mode: str | None = None
    seq_start: int | None = None
    seq_end: int | None = None
    #: 手动起止号范围不够时剩下的号要用的空号段 [[起, 止], …]（按从小到大使用）；不给为接在最大号之后
    seq_gaps: list[list[int]] | None = None


class RunIn(In):
    token: str | None = None


class RevokeConfigIn(In):
    """撤销所选物料行的配置（还没有号的行）。"""

    pi_no: str | None = None
    lines: list[LineRef] | None = None


class DeleteIn(In):
    """删号范围（三选一）：单据 bills / 物料行 lines（两者可同时给）/ SN 起止号 start_sn、end_sn（只填起号 = 单枚；
    起止号须在同一物料行）。原因选填。"""

    pi_no: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
    start_sn: str | None = None
    end_sn: str | None = None
    reason: str | None = None
