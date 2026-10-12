from app.schemas.common import In


class PiPreviewIn(In):
    pi_no: str | None = None
    qty: int | None = None
    rule_id: int | None = None


class PiGenerateItem(In):
    preview_token: str | None = None
    bill_no: str | None = None
    qty: int | None = None


class PiGenerateIn(In):
    pi_no: str | None = None
    items: list[PiGenerateItem] | None = None


class PiAllocateIn(In):
    pi_no: str | None = None


class RevokeIn(In):
    reason: str | None = None


class AllocateIn(In):
    """整单分配（不再接受数量；旧调用方传 qty 会被忽略）。"""

    bill_no: str | None = None
    factory_code: str | None = None


class AcquireIn(In):
    """页面领取 / 打印：工厂 + PI + 请求号（幂等）。"""

    factory_code: str | None = None
    pi_no: str | None = None
    request_no: str | None = None


class OpenAcquireIn(In):
    factory_code: str | None = None
    pi: str | None = None
    request_no: str | None = None
    page_size: int | None = None


class RangeIn(In):
    start_sn: str | None = None
    end_sn: str | None = None


class CallbackIn(In):
    batch_no: str | None = None
    target_status: str | None = None
    sns: list[str | None] | None = None
    ranges: list[RangeIn] | None = None
