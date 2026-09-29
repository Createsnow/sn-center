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
