"""Compare the actual dual UI observations without hiding business differences."""
import json
from pathlib import Path

root = Path.cwd()
run = root / "tools/.runtime/comparison-20261008"
folder = root / "frontend/test-results/stage49-ui11"
states = {
    kind: {s["label"]: s for s in json.loads(next(folder.glob("*-" + kind + "/states.json")).read_text("utf8"))}
    for kind in ("prototype", "migration")
}
assert len(states["prototype"]) == len(states["migration"]) == 19
differences = []
checks = []
for language in ("zh-CN", "en", "vi"):
    for suffix in ("nel", "separator"):
        label = language + "-" + suffix
        a, b = (states[kind][label]["messages"] for kind in states)
        assert len(a) == 1 and a == b, (label, a, b)
    for suffix in ("nel", "pager"):
        label = language + "-" + suffix
        a, b = (states[kind][label]["geometry"] for kind in states)
        for group in a:
            assert len(a[group]) == len(b[group]), (label, group)
            for index, (x, y) in enumerate(zip(a[group], b[group])):
                keys = ["rect"]
                if group not in ("input", "table", "pager", "pagerPrev", "pagerNext", "pagerNumber", "pagerSize", "pagerSelect", "pagerTotal"):
                    keys += ["color", "background", "border"]
                if group == "pagerSelect":
                    keys += ["text"]
                if group == "headers":
                    keys += ["textBoxes"]
                for key in keys:
                    if x[key] != y[key]:
                        differences.append({"label": label, "group": group, "index": index, "key": key, "prototype": x[key], "migration": y[key]})
            checks.append({"label": label, "group": group, "elements": len(a[group])})
api = json.loads((run / "stage49-api-comparison.json").read_text("utf8"))
assert api["python"]["audits"] == api["java"]["audits"]
assert len(api["java"]["audits"]) == 20
assert len(api["java"]["requests"]) == len(api["python"]["requests"]) == 20
result = {"pairedStates": 19, "exactLocalizedErrorPairs": 6, "geometryChecks": checks, "differences": differences,
          "directApiOperationsPerSystem": 20, "matchingAuditFacts": 20,
          "limits": ["Only the recorded administrator scenarios and desktop viewport are verified here.",
                     "This does not establish completion of P01-P16 or all request coercion and K3 response boundaries."]}
(run / "stage49-comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), "utf8")
print(json.dumps({"statePairs": 19, "localizedErrorPairs": 6, "geometryGroups": len(checks), "differences": differences}, ensure_ascii=False))
assert not differences
