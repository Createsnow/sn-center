from app.schemas.common import In
from app.schemas.rule import LineRef


class SyncIn(In):
    bill_no: str | None = None


class AttachIn(In):
    bill_no: str | None = None
    seq: int | None = None
    value: str | None = None


class LengthBatchIn(In):
    """统一设置长度的范围：物料行 / 单据 / 都不填 = 整张 PI。"""

    pi_no: str | None = None
    bills: list[str] | None = None
    lines: list[LineRef] | None = None
    value: str | None = None


class AlertHandleIn(In):
    """CONFIRM 确认变更 / MAP 确认对应（target_seq）/ UNRELATED 确认无关 / VOID 确认已作废（note 必填）/ ACK 知道了。"""

    action: str | None = None
    note: str | None = None
    target_seq: int | None = None


class MesConfigIn(In):
    """MES 对接配置；凭证留空 = 沿用已存的（不回显）。"""

    mes_url: str | None = None
    mes_auth_type: str | None = None
    mes_credential: str | None = None
    batch_size: int | None = None
    push_timeout_sec: int | None = None
    push_concurrency: int | None = None
    tz_offset_min: int | None = None


class MesToggleIn(In):
    enabled: bool = False


class BatchManualIn(In):
    reason: str | None = None
    basis: str | None = None


class ResumeIn(In):
    factory_code: str | None = None
    #: PI；转出批次为 #XO
    key: str | None = None


class DeliveryAlertIn(In):
    """RESEND 以本系统为准重推 / CLOSE 关闭；note 为核查结论（必填）。"""

    action: str | None = None
    note: str | None = None


class AcquireIn(In):
    factory_code: str | None = None
    pi_no: str | None = None
    request_no: str | None = None
