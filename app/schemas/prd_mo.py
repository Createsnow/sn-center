from app.schemas.common import In


class SyncIn(In):
    """不传 bill_no = 全量同步；传 bill_no = 只替换该单。"""

    bill_no: str | None = None
