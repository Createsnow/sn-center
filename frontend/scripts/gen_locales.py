#!/usr/bin/env python3
"""生成 src/locales/{zh-CN,en,vi}.json 与 errors.*.json。

UI 词条来自 ui_messages.py；错误码中文来自后端 ErrorCode.java，英 / 越来自 error_messages.py。
缺译或占位符不一致时直接失败。用法：python3 frontend/scripts/gen_locales.py
"""
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from ui_messages import M  # noqa: E402
from error_messages import E  # noqa: E402

ROOT = HERE.parent
JAVA = ROOT.parent / "backend/src/main/java/com/sncenter/common/ErrorCode.java"
PY = ROOT.parent / "app/core/errors.py"
OUT = ROOT / "src/locales"
PH = re.compile(r"\{(\w+)\}")
LANGS = ["zh-CN", "en", "vi"]


def nest(flat):
    out = {}
    for key, val in flat.items():
        node = out
        parts = key.split(".")
        for p in parts[:-1]:
            node = node.setdefault(p, {})
        node[parts[-1]] = val
    return out


def main():
    problems = []
    ui = {lang: {} for lang in LANGS}
    for key, vals in M.items():
        phs = [sorted(PH.findall(v)) for v in vals]
        if any(p != phs[0] for p in phs):
            problems.append(f"ui {key}: placeholders differ {phs}")
        for lang, v in zip(LANGS, vals):
            ui[lang][key] = v
    if JAVA.exists():
        zh_errors = dict(re.findall(r'^\s+([A-Z0-9_]+)\(\d+, "([^"]*)"\)', JAVA.read_text("utf-8"), re.M))
    else:  # 仓库里只有 Python 后端时取 app/core/errors.py
        zh_errors = dict(re.findall(r'^\s+([A-Z0-9_]+) = \(\d+, "([^"]*)"\)', PY.read_text("utf-8"), re.M))
    errs = {lang: {} for lang in LANGS}
    for code, zh in zh_errors.items():
        if code not in E:
            problems.append(f"error {code}: missing en/vi")
            continue
        en, vi = E[code]
        for lang, v in zip(LANGS, (zh, en, vi)):
            if sorted(PH.findall(v)) != sorted(PH.findall(zh)):
                problems.append(f"error {code} [{lang}]: placeholders differ from zh")
            errs[lang][code] = v
    for code in E:
        if code not in zh_errors:
            problems.append(f"error {code}: not in ErrorCode.java")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        sys.exit(1)
    OUT.mkdir(parents=True, exist_ok=True)
    for lang in LANGS:
        (OUT / f"{lang}.json").write_text(json.dumps(nest(ui[lang]), ensure_ascii=False, indent=2) + "\n", "utf-8")
        (OUT / f"errors.{lang}.json").write_text(json.dumps(errs[lang], ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(f"ok: {len(M)} ui keys, {len(zh_errors)} error codes x {len(LANGS)} languages")


if __name__ == "__main__":
    main()
