"""Compare real UI downloads before/after transfers, including header-only files."""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path

import openpyxl

spec = importlib.util.spec_from_file_location(
    "print_files", Path(__file__).with_name("compare-print-files.py")
)
basic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(basic)
SNS = [f"A17A{n:05}" for n in range(1, 4)]
EXPECTED = {
    "original-automatic.xlsx": ("original", SNS),
    "at-b-automatic.xlsx": ("at-b", SNS[:1]),
    "returned-automatic.xlsx": ("returned", SNS),
}
for checkpoint, identity, sns in (
    ("01-original", "original", SNS),
    ("02-partial", "original", SNS[1:]),
    ("03-reacquired", "original", SNS[1:]),
    ("03-at-b", "at-b", SNS[:1]),
    ("04-all-out", "original", []),
    ("05-returned-old", "original", []),
    ("06-returned-b", "at-b", []),
    ("07-returned-new", "returned", SNS),
):
    for fmt in ("csv", "xlsx"):
        EXPECTED[f"{checkpoint}-{identity}.{fmt}"] = (identity, sns)


def inspect_file(directory, name, identity, sns):
    path = directory / name
    raw = path.read_bytes()
    types = None
    if path.suffix == ".csv":
        assert raw.startswith(b"\xef\xbb\xbf"), name
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"))))
        for row in rows[1:]:
            row[0], row[3] = int(row[0]), int(row[3])
    else:
        book = openpyxl.load_workbook(path, data_only=False)
        assert book.sheetnames == [identity["printNo"]], name
        sheet = book.active
        assert sheet.freeze_panes == "A2", name
        cells = list(sheet.rows)
        rows = [[c.value for c in row] for row in cells]
        types = [[c.data_type for c in row] for row in cells]
        for row in cells[1:]:
            assert row[0].data_type == row[3].data_type == "n"
            assert row[1].data_type == row[2].data_type == "s"
        book.close()
    assert rows[0] == basic.HEADERS, name
    assert [row[1] for row in rows[1:]] == sns, (name, "raw current SN order")
    for no, row in enumerate(rows[1:], 1):
        assert len(row) == 11 and row[0] == no
        assert row[2] == row[1][-5:] and row[3] == int(row[2])
        assert row[4] == "CMP-UI-ACQ-PI-A"
        assert row[5] in ("CMP-ACQ-MAT-0", "CMP-ACQ-MAT-1")
        assert row[6] == "CMP-ACQ-CUSTOMER"
        assert row[7] == identity["factory"] and row[8] == "PRINTED"
        assert row[9] == identity["batchNo"]
        assert row[10] == "CMP-UI-ACQ-MO-A"
        row[9] = "<current-batch>"
    return {"rows": rows, "types": types, "sha256": hashlib.sha256(raw).hexdigest()}


def inspect(directory):
    candidates = list(directory.rglob("transfer-file-cases.json"))
    assert len(candidates) == 2, candidates
    source = next(p.parent for p in candidates if p.parent.name.endswith("-prototype"))
    target = next(p.parent for p in candidates if p.parent.name.endswith("-migration"))
    manifests = [
        json.loads((p / "transfer-file-cases.json").read_text())
        for p in (source, target)
    ]
    for manifest in manifests:
        assert {
            c["name"]: (c["identity"], c["sns"]) for c in manifest["fileCases"]
        } == EXPECTED
        assert len(manifest["fileCases"]) == 19
        identities = manifest["identities"]
        assert set(identities) == {"original", "at-b", "returned"}
        assert len({v["batchNo"] for v in identities.values()}) == 3
        assert len({v["printNo"] for v in identities.values()}) == 3
        for key, identity in identities.items():
            assert identity["quantity"] == (1 if key == "at-b" else 3)
            assert identity["factory"] == ("CMP-B" if key == "at-b" else "CMP-A")
    pairs = []
    for name, (key, sns) in EXPECTED.items():
        before, after = [
            inspect_file(parent, name, manifest["identities"][key], sns)
            for parent, manifest in zip((source, target), manifests)
        ]
        assert before["rows"] == after["rows"], (name, "eleven columns/order")
        assert before["types"] == after["types"], (name, "XLSX cell types")
        pairs.append(
            {
                "file": name,
                "dataRows": len(sns),
                "allColumnsAndOrderEqual": True,
                "prototypeSha256": before["sha256"],
                "migrationSha256": after["sha256"],
            }
        )
    return {
        "filePairs": pairs,
        "emptyFilePairs": sum(not sns for _, sns in EXPECTED.values()),
        "normalization": "Only independent current batch numbers; print titles match each actual UI record.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = inspect(args.directory)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Verified {len(result['filePairs'])} transfer download pairs.")
