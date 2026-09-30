from app.schemas.common import In


class SyncIn(In):
    """不传 bill_no = 全量同步；传 bill_no = 只替换该单。"""

    bill_no: str | None = None


class SyncScheduleIn(In):
    """定时全量同步设置：enabled 开关；interval_minutes 不传 = 沿用当前间隔。"""

    enabled: bool
    interval_minutes: int | None = None
