import logging

import structlog


def setup_logging(*, json_output: bool) -> None:
    """每行带 request_id（RequestIdMiddleware 绑定到 structlog 上下文）；production 输出单行 JSON。"""
    renderer = structlog.processors.JSONRenderer() if json_output else structlog.dev.ConsoleRenderer()
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.format_exc_info,
            renderer,
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
    )
