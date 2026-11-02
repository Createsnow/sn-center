"""日志：全部写到标准输出，由运行环境负责收集与轮转（Docker 见 docker-compose.yml 的 logging，K8s 由 kubelet 轮转）。

本系统的 structlog 日志与标准库 logging 的日志（uvicorn 访问 / 错误日志、第三方库）走同一个格式化器：
production 每行一条 JSON，其他环境为便于阅读的控制台格式；每行带 request_id（RequestIdMiddleware 绑定）。
"""

import logging
import sys

import structlog

#: 只记告警以上的第三方日志器（httpx 每个请求都记一条 INFO）
_QUIET = ("httpx", "httpcore")
#: uvicorn 自带处理器的日志器：去掉其处理器、交给根日志器统一输出
_UVICORN = ("uvicorn", "uvicorn.error", "uvicorn.access")


class _StdoutHandler(logging.StreamHandler):
    """每次写都取当前的 sys.stdout（测试替换标准输出后不会写到已关闭的流）。"""

    @property
    def stream(self):  # type: ignore[override]
        return sys.stdout

    @stream.setter
    def stream(self, _value) -> None:
        pass


class _SkipHealthProbe(logging.Filter):
    """不记成功的健康检查访问日志（Docker / K8s 探针每几秒一次，会淹没正常日志）。"""

    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if isinstance(args, tuple) and len(args) == 5:
            path, status = args[2], args[4]
            if isinstance(path, str) and path.startswith("/health/") and isinstance(status, int) and status < 400:
                return False
        return True


def _access_fields(_logger, _method: str, event_dict: dict) -> dict:
    """uvicorn 访问日志拆出字段：client、method、path、status（便于日志平台检索）；其他日志不输出格式化参数。"""
    args = event_dict.pop("positional_args", None)
    record = event_dict.get("_record")
    if record is not None and record.name == "uvicorn.access" and isinstance(args, tuple) and len(args) == 5:
        event_dict.update(client=args[0], method=args[1], path=args[2], status=args[4])
    return event_dict


def setup_logging(*, json_output: bool) -> None:
    shared = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_logger_name,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
    ]
    structlog.configure(
        processors=[*shared, structlog.stdlib.ProcessorFormatter.wrap_for_formatter],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
    )
    final = (
        [structlog.processors.format_exc_info, structlog.processors.JSONRenderer()]
        if json_output
        else [structlog.dev.ConsoleRenderer()]
    )
    handler = _StdoutHandler()
    handler.setFormatter(
        structlog.stdlib.ProcessorFormatter(
            foreign_pre_chain=[*shared, _access_fields],
            pass_foreign_args=True,
            processors=[structlog.stdlib.ProcessorFormatter.remove_processors_meta, *final],
        )
    )
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)
    # uvicorn 在导入应用之前已按它自己的格式装好处理器：清掉、改为向根日志器传递
    for name in _UVICORN:
        lg = logging.getLogger(name)
        lg.handlers = []
        lg.propagate = True
    access = logging.getLogger("uvicorn.access")
    access.filters = [f for f in access.filters if not isinstance(f, _SkipHealthProbe)]
    access.addFilter(_SkipHealthProbe())
    for name in _QUIET:
        logging.getLogger(name).setLevel(logging.WARNING)
