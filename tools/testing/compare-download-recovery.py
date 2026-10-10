"""核对 P09 三语下载故障后的真实文件、提示语义及窄屏详情证据。"""

import argparse
import csv
import importlib.util
import io
import json
from pathlib import Path


def inspect(directory):
    spec = importlib.util.spec_from_file_location(
        "print_files", Path(__file__).with_name("compare-print-files.py")
    )
    files = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(files)
    pairs, cases = [], []
    for source in sorted(directory.glob("*-prototype")):
        target = source.with_name(source.name.removesuffix("-prototype") + "-migration")
        assert target.is_dir(), target
        observations = [
            json.loads((parent / "download-recovery.json").read_text(encoding="utf-8"))
            for parent in (source, target)
        ]
        before, after = observations
        # 原始计时保留；浏览器轮询及退场动画会令三秒提示的测量略晚。
        assert 2000 < before["noticeElapsedMs"] < 4500
        assert 2000 < after["noticeElapsedMs"] < 4500
        assert {k: v for k, v in before.items() if k != "noticeElapsedMs"} == {
            k: v for k, v in after.items() if k != "noticeElapsedMs"
        }
        assert before["drawer"] == {"x": 140, "y": 0, "width": 760, "height": 900}
        assert before["successfulPrintCommands"] == before["injectedFailures"] == 2
        books = {}
        for filename in ("automatic-second.xlsx", "retried-first.xlsx"):
            books[filename] = [files.workbook(p / filename) for p in (source, target)]
            a, b = books[filename]
            assert a["rows"] == b["rows"] and a["types"] == b["types"]
            assert len(a["rows"]) == 3
            assert a["freeze"] == b["freeze"] == "A2"
            expected_pi = "CMP-UI-ACQ-PI-" + (
                "B" if filename.startswith("automatic") else "A"
            )
            assert all(row[4] == expected_pi for row in a["rows"])
            pairs.append(
                {
                    "locale": before["locale"],
                    "file": filename,
                    "rows": 3,
                    "allColumnsAndOriginalOrderEqual": True,
                    "cellTypesEqual": True,
                }
            )
        normalized_csv = []
        for parent, book in zip((source, target), books["retried-first.xlsx"]):
            raw = (parent / "retried-first.csv").read_bytes()
            assert raw.startswith(b"\xef\xbb\xbf")
            content = raw[3:].decode("utf-8")
            rows = list(csv.reader(io.StringIO(content)))
            assert rows[0] == files.HEADERS and len(rows) == 4
            assert all(row[9] == book["batchNo"] for row in rows[1:])
            assert [row[1] for row in rows[1:]] == [row[1] for row in book["rows"]]
            normalized_csv.append(content.replace(book["batchNo"], "<batch>"))
        assert normalized_csv[0] == normalized_csv[1]
        pairs.append(
            {
                "locale": before["locale"],
                "file": "retried-first.csv",
                "rows": 3,
                "utf8Bom": True,
                "bytesEqualExceptIndependentBatchNumbers": True,
            }
        )
        cases.append(
            {"locale": before["locale"], "prototype": before, "migration": after}
        )
    assert sorted(case["locale"] for case in cases) == ["en", "vi", "zh-CN"]
    assert len(pairs) == 9
    return {
        "filePairs": pairs,
        "cases": cases,
        "scope": "HTTP 503 injected only for downloads. Actual acquire/print commands, real retries, original file row order. No claim about real permission revocation or arbitrary network failure.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_text(
        json.dumps(inspect(args.directory), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print("Verified three language pairs and nine complete download pairs.")
