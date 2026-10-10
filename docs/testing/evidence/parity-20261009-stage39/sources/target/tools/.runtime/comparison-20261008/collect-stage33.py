"""Freeze stage33 final evidence once; keep diagnostic attempts and secrets separate."""
from pathlib import Path
import ast
import hashlib
import json
import re
import shutil

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
dest = root / 'docs/testing/evidence/parity-20261009-stage33'
assert not dest.exists(), 'Never overwrite frozen or partial evidence'
ui = json.loads((run / 'stage33-sealed-ui-results.json').read_text('utf8'))
assert ui['stats']['expected'] == 8 and all(ui['stats'][k] == 0 for k in ('unexpected', 'skipped', 'flaky'))
comparison = json.loads((run / 'stage33-comparison.json').read_text('utf8'))
assert len(comparison['tables']) == len(comparison['layouts']) == 15
assert len(comparison['files']) == 4 and len(comparison['realRules']) == 2
state = json.loads((run / 'stage33-final-state.json').read_text('utf8'))
assert state['noActiveTasksOrOwnedFixtures'] and state['java']['schema'] == '35'
unit = json.loads((root / 'frontend/test-results/stage33-unit.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests'] == 78 and unit['numFailedTests'] == 0
suites = []
for name in ('stage33-verify2.log', 'stage33-verify4.log'):
    log = (run / name).read_text('utf8')
    if name.endswith('4.log'):
        assert 'BUILD SUCCESS' in log
    for n, f, e, s, suite in re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: .*? -- in ([\w.]+)', log):
        if name.endswith('2.log') and suite not in ('com.adtek.server.SnFoundationIT', 'com.adtek.server.SnMaintenanceIT'):
            continue
        suites.append({'tests': int(n), 'failures': int(f), 'errors': int(e), 'skipped': int(s), 'name': suite, 'log': name})
assert sum(v['tests'] for v in suites if not v['name'].endswith('IT')) == 87
assert sum(v['tests'] for v in suites if v['name'].endswith('IT')) == 57
assert all(v['failures'] == v['errors'] == v['skipped'] == 0 for v in suites)
assert 'Total: 183 tests in 23 files' in (run / 'stage33-registered-ui.log').read_text('utf8')
secrets = []
private = root / 'deploy/.secrets/comparison-20261008'
for file in private.iterdir():
    if file.is_file() and file.name not in ('init.sql', 'role-accounts.json'):
        value = file.read_text('utf8').strip()
        if len(value) > 8:
            secrets.append(value)
for system in json.loads((private / 'role-accounts.json').read_text()).values():
    for account in system.values():
        secrets.extend(v for v in account.values() if isinstance(v, str) and len(v) > 8)
# Read-only reuse of the reviewed redaction/copy helpers, without executing the old collector.
old = ast.parse((run / 'collect-stage28.py').read_text('utf8'))
functions = [n for n in old.body if isinstance(n, ast.FunctionDef) and n.name in ('scrub', 'copy', 'tree')]
assert len(functions) == 3
exec(compile(ast.Module(body=functions, type_ignores=[]), 'reviewed-copy-helpers', 'exec'))
tree(root / 'frontend/test-results/stage33-sealed-ui', dest / 'ui')
for attempt in ('rule-initial', 'rule-diagnostic', 'verified', 'final'):
    tree(root / f'frontend/test-results/stage33-{attempt}-ui', dest / 'diagnostics' / attempt)
    report = run / f'stage33-{attempt}-ui-results.json'
    if report.exists():
        copy(report, dest / 'diagnostics' / f'{attempt}-results.json')
for file in run.glob('stage33-*.log'):
    copy(file, dest / 'diagnostics' / file.name)
for name in ('stage33-sealed-ui-results.json', 'stage33-sealed-ui.log', 'stage33-comparison.json',
             'stage33-final-state.json', 'stage33-verify4.log', 'stage33-web-checks.log',
             'stage33-frontend-final-format-check.log', 'stage33-format-check4.log',
             'stage33-final-static.log', 'stage33-registered-ui.log', 'stage33-python-check.log',
             'stage33-doc-check.log'):
    copy(run / name, dest / name)
copy(root / 'frontend/test-results/stage33-unit.json', dest / 'frontend-unit.json')
for name in ('stage33-compare.py', 'stage33-final-state.py', 'collect-stage33.py'):
    copy(run / name, dest / 'verification' / name)
(dest / 'backend-suites.json').write_text(json.dumps(suites, indent=2), 'utf8')
sources = set(json.loads((root / 'docs/testing/evidence/parity-20261009-stage32/summary.json').read_text('utf8'))['sourceHashes'])
sources.update([
    'backend/adtek-server/src/main/resources/db/migration/V35__audit_text_details_and_optional_quantity.sql',
    'backend/adtek-system/src/main/java/com/adtek/system/service/support/AuditChanges.java',
    'backend/adtek-sn/src/main/java/com/adtek/sn/service/impl/RuleServiceImpl.java',
    'backend/adtek-sn/src/main/java/com/adtek/sn/service/impl/SnAuditObserver.java',
    'backend/adtek-sn/src/main/java/com/adtek/sn/mapper/MaintenanceMapper.java',
    'backend/adtek-server/src/test/java/com/adtek/server/SnFoundationIT.java',
    'backend/adtek-server/src/test/java/com/adtek/server/SnMaintenanceIT.java',
    'backend/adtek-sn/src/test/resources/audit-file-goldens/v4.json',
    'frontend/e2e/parity/audit-rules.spec.ts',
    'tools/testing/parity-rule-fixture.py',
    '../sn-center-python/app/services/rules.py',
    '../sn-center-python/app/services/audit.py',
    'docs/architecture/数据库设计.md',
    'docs/business/接口契约.md',
    'docs/business/转厂查询与痕迹契约.md',
    'tools/.runtime/comparison-20261008/stage33-compare.py',
    'tools/.runtime/comparison-20261008/stage33-final-state.py',
    'tools/.runtime/comparison-20261008/collect-stage33.py',
])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
hashes = {name: sha(root / name) for name in sorted(sources)}
summary = {
    'stage': 33, 'goalComplete': False, 'allCasesRerun': False,
    'ui': ui['stats'], 'workers': 1, 'retries': 0, 'registeredCases': 183, 'distinctCasesRun': 8,
    'uiScreenshots': len(list((dest / 'ui').rglob('*.png'))),
    'orderedRawTablePairs': 15, 'exactMainLayoutPairs': 15, 'exactExpandedDetailCells': 20,
    'fullCellValueTypeOrderFilePairs': 4, 'realRuleFilePairsWith21OrderedColumnsAndAllTypes': 2,
    'realRuleRecordsPerSide': 7, 'realRuleAuditProductionVerified': True,
    'allRealRuleFileCellsEqual': False, 'realRuleFileDifferingFields': ['time', 'configuredAdminName', 'traceId'],
    'crossImplementationZipBytesEqual': False, 'legacyActualWorkbookSha256Versions': [1, 2, 3, 4],
    'backendUnitTests': 87, 'backendIntegrationTests': 57, 'backendIntegrationOneRun': False,
    'frontendUnitTests': 78, 'schema': '35', 'nextMigration': '36', 'currentNewAuditExportFormat': 5,
    'sourceHashes': hashes,
    'remaining': [
        'P12 login/platform events, other real business producers, failures, batch/transfer linkage and action families',
        'P12 other roles/permissions/recovery/capacity/full styling; old audit creation order',
        'P11 remaining states/filters/copy/errors/capacity/performance/full old-format recovery UI',
        'P01-P16 full acceptance; source page size race, pre-V31 SN order, 500000-row import performance',
    ],
    'diagnostics': [
        'Initial rule test matched page-load response instead of filtered response; predicate corrected',
        'Valid pre-fix UI: prototype 2 passed, migration 2 failed with missing/collapsed real audit records',
        'Checkstyle lines and test helper compile failure, then MySQL mixed-collation keyword failure corrected',
        'New integration test initially called query export instead of audit export; corrected helper and worker drain',
        'First post-fix UI 6/8: real records matched, new rule test used nonexistent default Ant expansion selector; corrected to existing custom button',
        'First 8-pass final UI kept log/screenshots; default JSON later overwritten by case listing. Sealed run uses explicit OUTPUT_FILE with complete separate report',
        'Preservation checker initially assumed all frozen manifests were dictionaries; stage26 uses a list, now both verified without altering old evidence',
    ],
    'warnings': ['largeBundle', 'xlsxDefaultStyleWarning', 'WindowsSourceCarriageReturnsReadAsLineFeeds'],
}
assert summary['uiScreenshots'] == 50
(dest / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json', '.log', '.csv', '.py'):
        content = file.read_text('utf-8-sig')
        assert not any(secret in content for secret in secrets), file.name
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'files': len(manifest), 'sources': len(hashes), 'screenshots': 50, 'goalComplete': False}))
