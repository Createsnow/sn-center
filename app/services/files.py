"""流式写 CSV（UTF-8 BOM，Excel 可直接打开）或 XLSX（openpyxl 只写模式）。

调用方分页读库、逐行 add；CSV 边写边吐给客户端，XLSX 写完后整体输出。
"""

from __future__ import annotations

import io
import tempfile
from collections.abc import Iterable, Iterator
from typing import Any

from openpyxl import Workbook
from openpyxl.cell import WriteOnlyCell

XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
CSV_TYPE = "text/csv; charset=utf-8"
_BIG = 999_999_999_999_999


def fmt_of(f: str | None) -> str:
    return "xlsx" if (f or "").lower() == "xlsx" else "csv"


def content_type(fmt: str) -> str:
    return XLSX_TYPE if fmt == "xlsx" else CSV_TYPE


def _num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def csv_line(cells: list[Any]) -> str:
    parts = []
    for v in cells:
        s = "" if v is None else ("true" if v is True else "false" if v is False else str(v))
        if any(ch in s for ch in ',"\n\r'):
            s = '"' + s.replace('"', '""') + '"'
        # 防止以 = + - @ 开头的值在 Excel 中被当成公式执行
        if s and s[0] in "=+-@" and not _num(v):
            s = "'" + s
        parts.append(s)
    return ",".join(parts) + "\r\n"


def csv_stream(headers: list[str], rows: Iterable[list[Any]]) -> Iterator[bytes]:
    yield b"\xef\xbb\xbf" + csv_line(headers).encode("utf-8")
    buf: list[str] = []
    for r in rows:
        buf.append(csv_line(r))
        if len(buf) >= 500:
            yield "".join(buf).encode("utf-8")
            buf.clear()
    if buf:
        yield "".join(buf).encode("utf-8")


def xlsx_bytes(title: str, headers: list[str], rows: Iterable[list[Any]]) -> Iterator[bytes]:
    """冻结首行；数字写数值，超长整数写文本。"""
    wb = Workbook(write_only=True)
    ws = wb.create_sheet(title[:31] or "Sheet1")
    ws.freeze_panes = "A2"
    ws.append(headers)
    for r in rows:
        line = []
        for v in r:
            if _num(v) and not (isinstance(v, int) and abs(v) > _BIG):
                line.append(v)
            elif v is None:
                line.append(None)
            else:
                line.append(WriteOnlyCell(ws, value=str(v)))
        ws.append(line)
    with tempfile.SpooledTemporaryFile(max_size=32 * 1024 * 1024) as tmp:
        wb.save(tmp)
        tmp.seek(0)
        while True:
            chunk = tmp.read(1024 * 1024)
            if not chunk:
                break
            yield chunk


def stream(fmt: str, title: str, headers: list[str], rows: Iterable[list[Any]]) -> Iterator[bytes]:
    return xlsx_bytes(title, headers, rows) if fmt == "xlsx" else csv_stream(headers, rows)


def to_bytes(chunks: Iterable[bytes]) -> bytes:
    out = io.BytesIO()
    for c in chunks:
        out.write(c)
    return out.getvalue()
