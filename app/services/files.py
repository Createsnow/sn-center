"""导出文件一律为 XLSX（openpyxl 只写模式）：文本单元格按字符串原样写出，不加防公式前缀，SN 不会被改动。

调用方分页读库、逐行 add；写完后整体输出。
"""

from __future__ import annotations

import io
import tempfile
from collections.abc import Iterable, Iterator
from typing import Any

from openpyxl import Workbook
from openpyxl.cell import WriteOnlyCell

XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
_BIG = 999_999_999_999_999


def _num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _text_cell(ws: Any, v: Any) -> WriteOnlyCell:
    """文本单元格：强制按字符串写出，以 = 开头也不会被 openpyxl 写成公式。"""
    cell = WriteOnlyCell(ws, value=str(v))
    cell.data_type = "s"
    return cell


def xlsx_bytes(title: str, headers: list[str], rows: Iterable[list[Any]]) -> Iterator[bytes]:
    """冻结首行；数字写数值，超长整数写文本。"""
    wb = Workbook(write_only=True)
    ws = wb.create_sheet(title[:31] or "Sheet1")
    ws.freeze_panes = "A2"
    ws.append([_text_cell(ws, h) for h in headers])
    for r in rows:
        line = []
        for v in r:
            if _num(v) and not (isinstance(v, int) and abs(v) > _BIG):
                line.append(v)
            elif v is None:
                line.append(None)
            else:
                line.append(_text_cell(ws, v))
        ws.append(line)
    with tempfile.SpooledTemporaryFile(max_size=32 * 1024 * 1024) as tmp:
        wb.save(tmp)
        tmp.seek(0)
        while True:
            chunk = tmp.read(1024 * 1024)
            if not chunk:
                break
            yield chunk


def to_bytes(chunks: Iterable[bytes]) -> bytes:
    out = io.BytesIO()
    for c in chunks:
        out.write(c)
    return out.getvalue()
