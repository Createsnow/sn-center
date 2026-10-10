"""Inspect actual paired P09 downloads; only volatile business numbers are normalized."""

import argparse
import csv
import hashlib
import io
import json
import re
from pathlib import Path

import openpyxl

HEADERS = [
    "序号",
    "完整SN",
    "进制流水",
    "十进制流水",
    "PI",
    "物料编码",
    "客户编码",
    "工厂",
    "状态",
    "领取批次",
    "来源订单",
]


def workbook(path):
    book = openpyxl.load_workbook(path, data_only=False)
    assert len(book.worksheets) == 1
    sheet = book.active
    assert re.fullmatch(r"P\d{18}", sheet.title), sheet.title
    assert sheet.freeze_panes == "A2"
    cells = list(sheet.rows)
    assert [c.value for c in cells[0]] == HEADERS
    values, types, batches = [], [], set()
    for no, row in enumerate(cells[1:], 1):
        data = [c.value for c in row]
        assert len(data) == 11 and data[0] == no
        assert re.fullmatch(r"B\d{18}", data[9]), data[9]
        batches.add(data[9])
        assert row[0].data_type == "n" and row[3].data_type == "n"
        assert row[1].data_type == "s" and row[2].data_type == "s"
        assert data[8] == "PRINTED"
        # 两次独立业务的单号不同，但单号必须合法且同文件引用同一真实批次。
        data[9] = "<batch:" + data[4] + ">"
        values.append(data)
        types.append([c.data_type if c.value is not None else "blank" for c in row])
    assert len(batches) == 1
    return {
        "rows": values,
        "types": types,
        "batchNo": next(iter(batches)),
        "printNo": sheet.title,
        "freeze": sheet.freeze_panes,
    }


def inspect(directory, expected_pairs=7):
    pairs = []
    for prototype in directory.glob("*-prototype"):
        migration = directory / (
            prototype.name.removesuffix("-prototype") + "-migration"
        )
        assert migration.is_dir(), migration
        for path in sorted(prototype.glob("*.xlsx")):
            target = migration / path.name
            assert target.exists(), target
            before, after = workbook(path), workbook(target)
            assert before["rows"] == after["rows"], (path.name, "row values/order")
            assert before["types"] == after["types"], (path.name, "cell types")
            pair = {
                "case": prototype.name.removesuffix("-prototype"),
                "file": path.name,
                "rows": len(before["rows"]),
                "rawSnOrderEqual": True,
                "allElevenColumnsEqualAfterBusinessNumberNormalization": True,
                "cellTypesEqual": True,
                "freezePanes": "A2",
            }
            for name, parent, info in (
                ("prototype", prototype, before),
                ("migration", migration, after),
            ):
                if path.name.startswith("single"):
                    identity = json.loads(
                        (parent / "file-identities.json").read_text(encoding="utf-8")
                    )
                    assert info["batchNo"] == identity["batchNo"]
                    assert info["printNo"] == identity["printNo"]
                pair[name + "Sha256"] = hashlib.sha256(
                    (parent / path.name).read_bytes()
                ).hexdigest()
            pairs.append(pair)
        source_csv = prototype / "single.csv"
        if source_csv.exists():
            normalized = []
            for parent in (prototype, migration):
                raw = (parent / "single.csv").read_bytes()
                assert raw.startswith(b"\xef\xbb\xbf")
                identity = json.loads(
                    (parent / "file-identities.json").read_text(encoding="utf-8")
                )
                content = raw[3:].decode("utf-8")
                rows = list(csv.reader(io.StringIO(content)))
                assert rows[0] == HEADERS
                assert all(row[9] == identity["batchNo"] for row in rows[1:])
                normalized.append(content.replace(identity["batchNo"], "<batch>"))
                xlsx = workbook(parent / "single.xlsx")
                again = workbook(parent / "single-again.xlsx")
                assert xlsx == again
            assert normalized[0] == normalized[1], (
                "CSV differs beyond independent batch numbers"
            )
            pairs.append(
                {
                    "case": prototype.name.removesuffix("-prototype"),
                    "file": "single.csv",
                    "bom": True,
                    "encoding": "UTF-8",
                    "bytesEqualAfterBatchNumberNormalization": True,
                }
            )
    assert len(pairs) == expected_pairs, len(pairs)
    return {
        "filePairs": pairs,
        "note": "Independent B/P numbers and ZIP metadata differ. All columns, raw row order, cell types and CSV encoding/quoting are checked, not an SN-sorted shared-field projection.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--expected-pairs", type=int, default=7)
    args = parser.parse_args()
    args.output.write_text(
        json.dumps(
            inspect(args.directory, args.expected_pairs), ensure_ascii=False, indent=2
        ),
        encoding="utf-8",
    )
    print(
        f"Verified {args.expected_pairs} file pairs, including historical re-downloads."
    )
