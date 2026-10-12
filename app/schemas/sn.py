from pydantic import BaseModel, Field

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


# ---------------------------------------------------------------- 对外接口响应（只用于 /docs 说明与输出校验）


class SnItemOut(BaseModel):
    """一枚 SN。可空字段为空时值为 null，字段本身总会出现；时间格式 yyyy-MM-dd HH:mm:ss。"""

    id: int = Field(description="内部 id，仅用于排查，对接不要依赖")
    gen_month: int = Field(description="生成月份 yyyyMM", examples=[202609])
    sn: str = Field(description="完整 SN，打标用这个", examples=["81260908201001"])
    seq_text: str = Field(description="按规则编码后的流水；无规则或无法编码时为空字符串", examples=["01001"])
    seq_dec: int = Field(description="十进制流水，同一 seq_pi_no 下唯一", examples=[1001])
    seq_pi_no: str = Field(description="流水归属 PI（转厂不变）")
    pi_no: str = Field(description="当前业务 PI")
    customer_code: str = Field(description="客户编码")
    material_code: str = Field(description="物料编码；没有物料时为空字符串")
    factory_code: str | None = Field(description="当前工厂编码；批次里的号一定有值")
    bill_no: str = Field(description="来源生产订单编号")
    status: str = Field(description="TO_PRINT 待打印 / PRINTED 已打印 / APPLYING 转厂申请中", examples=["TO_PRINT"])
    batch_no: str | None = Field(description="领取批次号")
    print_no: str | None = Field(description="页面打印单号；MES 回调不产生打印单，为 null")
    created_at: str = Field(description="生成时间")
    allocated_at: str | None = Field(description="分配到工厂的时间")
    acquired_at: str | None = Field(description="领取时间")
    printed_at: str | None = Field(description="改为已打印的时间（页面打印或回调）；未打印为 null")


class SnPageOut(BaseModel):
    """号的分页结果。"""

    items: list[SnItemOut] = Field(description="本页的号；超出最后一页时为空数组")
    total: int = Field(description="总枚数；total_capped 为 true 时只是下限")
    page: int = Field(description="实际使用的页码（小于 1 按 1）")
    page_size: int = Field(description="实际使用的每页条数（超过 5000 按 5000）")
    total_capped: bool = Field(description="批次明细恒为 false")
