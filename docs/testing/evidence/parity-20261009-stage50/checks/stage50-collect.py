"""Freeze stage50 observations, checks and changed sources without private snapshots."""
from pathlib import Path
import hashlib, json, re, shutil, xml.etree.ElementTree as ET

root = Path.cwd()
run = root / "tools/.runtime/comparison-20261008"
dest = root / "docs/testing/evidence/parity-20261009-stage50"
assert not (dest / "manifest.json").exists(), "Frozen evidence must not be overwritten"
dest.mkdir(parents=True, exist_ok=True)
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()

def copy(source, name):
    target = dest / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)

ui = json.loads((run / "stage50-ui3-results.json").read_text("utf8"))
assert ui["stats"]["expected"] == 2 and ui["stats"]["unexpected"] == ui["stats"]["flaky"] == ui["stats"]["skipped"] == 0
copy(run / "stage50-ui3-results.json", Path("ui/results.json"))
folder = root / "frontend/test-results/stage50-ui3"
for p in folder.rglob("*"):
    if p.is_file():
        copy(p, Path("ui/final") / p.relative_to(folder))
for group in ("ui1", "ui2"):
    folder = root / "frontend/test-results" / ("stage50-" + group)
    for p in folder.rglob("*"):
        if p.is_file():
            copy(p, Path("diagnostics") / group / p.relative_to(folder))
for p in run.glob("stage50-ui*-results.json"):
    if p.name != "stage50-ui3-results.json":
        copy(p, Path("diagnostics/results") / p.name)
copy(run / "stage50-baseline-differences.json", Path("diagnostics/baseline-differences.json"))
copy(run / "stage50-ui2-comparison.json", Path("diagnostics/ui2-comparison.json"))
for category in ("backend", "frontend", "file", "frozen-sql", "prototype"):
    copy(run / ("stage50-" + category + "-hashes.json"), Path("checks/baselines") / (category + "-hashes.json"))
for p in run.glob("stage50-*.log"):
    copy(p, Path("logs") / p.name)
for name in ("comparison", "final-state", "dedicated-state", "services", "backend-summary"):
    copy(run / ("stage50-" + name + ".json"), Path("checks") / ("stage50-" + name + ".json"))
assert not json.loads((run / "stage50-comparison.json").read_text("utf8"))["differences"]
for name in ("compare", "final-state", "dedicated-check", "collect"):
    copy(run / ("stage50-" + name + ".py"), Path("checks") / ("stage50-" + name + ".py"))
copy(run / "stage50-stop-dedicated.ps1", Path("checks/stage50-stop-dedicated.ps1"))
assert json.loads((run / "stage50-services.json").read_text("utf-8-sig"))["dedicatedPortsClosed"]
units = json.loads((root / "frontend/test-results/stage50-unit.json").read_text("utf8"))
assert units["numPassedTests"] == 84 and units["numFailedTests"] == 0
copy(root / "frontend/test-results/stage50-unit.json", Path("checks/frontend-unit.json"))
log = (run / "stage50-backend.log").read_text("utf8", errors="replace")
assert "BUILD SUCCESS" in log
tests = []
for xml in (root / "backend").glob("*/target/*-reports/TEST-*.xml"):
    t = ET.parse(xml).getroot()
    name = t.get("name")
    if name.endswith("IT") and name not in ("com.adtek.server.SnAuditIT", "com.adtek.server.SnFoundationIT"):
        continue
    if not name.endswith("IT") and not re.search(r"-- in " + re.escape(name) + r"\b", log):
        continue
    assert int(t.get("failures")) == int(t.get("errors")) == int(t.get("skipped")) == 0, name
    tests.append({"name": name, "count": int(t.get("tests")), "seconds": float(t.get("time"))})
    copy(xml, Path("backend") / xml.name)
assert sum(t["count"] for t in tests if t["name"].endswith("IT")) == 60
assert sum(t["count"] for t in tests if not t["name"].endswith("IT")) == 94
sources = set()
for kind in ("backend", "frontend"):
    before = json.loads((run / ("stage50-" + kind + "-hashes.json")).read_text("utf8"))
    for name, digest in before.items():
        if sha(root / name) != digest:
            sources.add(name)
sources.update(["frontend/openapi/adtek-sn.json", "frontend/e2e/parity/k3-responses.spec.ts",
                "frontend/playwright.parity.config.ts", "tools/testing/parity-order-audit-fixture.py",
                "tools/testing/parity-k3-fixture.py"])
for name in sorted(sources):
    assert not Path(name).is_absolute() and ".." not in Path(name).parts
    copy(root / name, Path("sources") / name)
screenshots = len(list((dest / "ui").rglob("*.png")))
diagnostics = len(list((dest / "diagnostics").rglob("*.png")))
summary = {
    "date": "2026-10-09", "goal": "active", "schema": 41,
    "backend": tests, "frontendUnit": 84, "ui": ui["stats"],
    "screenshots": screenshots, "diagnosticScreenshots": diagnostics,
    "registeredUiTests": 227, "registeredUiFiles": 40, "fullUiMatrixRerun": False,
    "statePairs": 64, "businessColumnsPerRow": 11, "expandedAuditDetailsPerSystem": 39,
    "matchingAuditFacts": 39, "sourceFiles": {n: sha(root / n) for n in sorted(sources)},
    "mainDataPreserved": True, "dedicatedServicesStopped": True,
    "remaining": ["Full K3 response/error cases and request coercion/oversized boundaries",
                  "P01-P16 complete UI/role/language/viewport matrix",
                  "Generation after factory changes, concurrency, recovery, MES and full UI business loop",
                  "Scheduled internal SQL text, table ellipsis/tooltips and remaining dialog/visual details"]
}
(dest / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), "utf8")
values = []
private = root / "deploy/.secrets/comparison-20261008"
for folder in (private, private / "stage47", private / "stage48"):
    for name in ("java-admin-password", "python-admin-password", "java-admin-initial-password",
                 "python-admin-initial-password", "mysql-root", "mysql-java", "mysql-python", "mysql-pytest", "redis", "jwt"):
        p = folder / name
        if p.exists():
            values.append(p.read_bytes().strip())
for p in dest.rglob("*"):
    if p.is_file():
        content = p.read_bytes()
        assert not any(len(v) > 15 and v in content for v in values), p
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob("*")) if p.is_file()}
(dest / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), "utf8")
print(json.dumps({"files": len(manifest), "sources": len(sources), "screenshots": screenshots,
                  "diagnosticScreenshots": diagnostics, "backendUnit": 94, "integration": 60, "knownSecretsScan": "passed"}))
