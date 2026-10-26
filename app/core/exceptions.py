from app.core import utils as util


class AppError(Exception):
    status_code = 400
    code = "app_error"

    def __init__(self, message: str, status_code: int | None = None, code: str | None = None):
        super().__init__(message)
        self.message = message
        if status_code is not None:
            self.status_code = status_code
        if code is not None:
            self.code = code


class BizError(AppError):
    """业务拒绝。HTTP 体 {"code", "message", "params"}。

    message 为中文（日志、审计、兼容调用方）；code + params 供前端按当前语言渲染。
    由 app.core.errors.biz(ErrorCode.X, **params) 生成。
    """

    code = "biz_error"

    def __init__(
        self,
        message: str,
        status_code: int | None = None,
        code: str | None = None,
        params: dict | None = None,
    ):
        super().__init__(message, status_code, code)
        self.params = params or {}

    @property
    def status(self) -> int:
        return self.status_code


class MesError(AppError):
    """MES 接口（/api/mes/v1）的错误：{"schemaVersion", "code", "message", "batchNo", "timestamp", "details"}，
    字段固定齐全（batchNo 可为 null，details 可为空数组），与成功应答同一套信封。

    程序只按 code 判断，message 给人看。出错不返回 200。
    """

    code = "MES_ERROR"

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        batch_no: str | None = None,
        details: list[dict] | None = None,
        retry_after: int | None = None,
    ):
        super().__init__(message, status_code, code)
        self.batch_no = batch_no
        self.details = details or []
        self.retry_after = retry_after

    def body(self) -> dict:
        return mes_body(self.code, self.message, self.batch_no, details=self.details)


def mes_body(code: str, message: str, batch_no: str | None = None, **data) -> dict:
    """MES 接口应答信封（成功与出错相同）：协议版本、结果码、说明、相关批次号、应答时间（UTC+8），再加各接口的数据。"""
    return {
        "schemaVersion": "1.0",
        "code": code,
        "message": message,
        "batchNo": batch_no,
        "timestamp": util.mes_now(),
        **data,
    }
