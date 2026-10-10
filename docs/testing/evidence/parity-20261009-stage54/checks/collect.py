"""Freeze local functional acceptance, including failed diagnostics without relabeling them."""
from pathlib import Path
import hashlib, json, shutil

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
dest = root / 'docs/testing/evidence/parity-20261009-stage54'
assert not dest.exists()
dest.mkdir(parents=True)
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()

def copy(p, name):
    target = dest / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, target)

stats = {}
for category, groups in [('runs', ['mes2', 'functional-final', 'business-final']),
                         ('diagnostics', ['mes1', 'functional1', 'functional2', 'functional3'])]:
    for group in groups:
        report = run / f'stage54-{group}-results.json'
        value = json.loads(report.read_text('utf8'))
        if category == 'runs':
            assert value['stats']['expected'] == 2 and value['stats']['unexpected'] == 0
            stats[group] = value['stats']
        copy(report, Path(category) / group / 'results.json')
        folder = root / 'frontend/test-results' / ('stage54-' + group)
        for p in folder.rglob('*'):
            if p.is_file() and p.suffix in ('.json', '.png', '.xlsx'):
                copy(p, Path(category) / group / 'artifacts' / p.relative_to(folder))

for p in run.glob('stage54-*.log'):
    copy(p, Path('logs') / p.name)
for name in ('comparison', 'business-comparison', 'final-state', 'dedicated-state', 'services'):
    copy(run / ('stage54-' + name + '.json'), Path('checks') / (name + '.json'))
for name in ('compare', 'loop-compare', 'final-state', 'dedicated-check', 'collect'):
    copy(run / ('stage54-' + name + '.py'), Path('checks') / (name + '.py'))
for group in ('frontend', 'backend', 'prototype', 'file', 'frozen-sql'):
    copy(run / f'stage54-{group}-hashes.json', Path('checks/baselines') / (group + '.json'))
sources = ['frontend/e2e/parity/business-loop.spec.ts', 'frontend/e2e/parity/mes-probe.ts',
           'frontend/e2e/parity/final-functional.spec.ts', 'frontend/e2e/privacy.ts',
           'frontend/playwright.parity.config.ts', 'tools/testing/parity-order-audit-fixture.py']
for name in sources:
    copy(root / name, Path('sources') / name)
backend = json.loads((run / 'stage54-backend-hashes.json').read_text('utf8'))
assert all(sha(root / n) == v for n, v in backend.items())
frontend = json.loads((run / 'stage54-frontend-hashes.json').read_text('utf8'))
assert all(sha(root / n) == v for n, v in frontend.items() if n.startswith('frontend/src/'))
jar = sha(root / 'backend/adtek-server/target/adtek-server-acceptance.jar')
assert jar == (run / 'stage54-jar-sha256.txt').read_text('utf8').strip().lower()
units = json.loads((root / 'frontend/test-results/stage54-unit.json').read_text('utf8'))
assert units['numPassedTests'] == 84 and units['numFailedTests'] == 0
copy(root / 'frontend/test-results/stage54-unit.json', Path('checks/frontend-unit.json'))
assert json.loads((run / 'stage54-services.json').read_text('utf-8-sig'))['dedicatedPortsClosed']
comparison = json.loads((run / 'stage54-comparison.json').read_text('utf8'))
state = json.loads((run / 'stage54-final-state.json').read_text('utf8'))
assert all(v.get('allRowsUnchanged', v.get('originalRowsUnchanged', False)) for k in ('java','python') for v in state[k].values())
summary = {
    'date': '2026-10-09', 'acceptance': 'Local core functional acceptance closed; retained differences explicitly documented.',
    'userPriority': 'Stop cosmetic expansion; complete practical functional acceptance.',
    'schema': 41, 'runs': stats, 'finalPairedUiTests': 6,
    'frontendUnit': 84, 'backendRerun': False, 'backendBytesUnchanged': len(backend),
    'productFrontendBytesUnchangedThisStage': True, 'jarSha256': jar,
    'sourceFiles': {n: sha(root/n) for n in sources},
    'mesStatePairs': len(comparison['mesStatePairs']), 'mesBusinessPairs': len(comparison['mesBusinessPairs']),
    'pageEntriesPerSystem': len(comparison['pageEntriesPerSystem']), 'functionalFactPairs': len(comparison['functionalFacts']),
    'businessStatePairs': 4, 'actualPrintXlsxFiles': 4, 'coreUiAuditFactsPerSystem': 7,
    'screenshots': len(list((dest/'runs').rglob('*.png'))),
    'diagnosticScreenshots': len(list((dest/'diagnostics').rglob('*.png'))),
    'originalFilesPreserved': state['file']['files'], 'prototypeFilesPreserved': state['prototype']['files'],
    'frozenSqlPreserved': state['frozen-sql']['files'], 'allMainDatabaseRowsUnchanged': True,
    'dedicatedServicesStopped': True, 'mainServicesReady': True, 'fullUiMatrixRerun': False,
    'protocolIdentityClaimed': False, 'productionIntegrationClaimed': False,
    'diagnosticsNote': 'mes1 used wrong factory listing endpoint. functional1 asserted a removed form and encountered a fixture ID collision. functional2 expected generated rather than historical-import status. functional3 passed operations but observed headings before navigation completed. Corrected final observations compared equal; failed runs retained as diagnostics.',
    'retainedDifferences': ['Approved MES authentication/task/cursor contract', 'Prototype material-selector defect is not duplicated', 'Prototype page-size request race is not duplicated', 'Future-dated audit upper bound', 'Historical data/order reconstruction excluded; large-import performance differs']
}
(dest/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf8')
private = root / 'deploy/.secrets/comparison-20261008'
values = []
for folder in (private, private/'stage47', private/'stage48'):
    for name in ('java-admin-password','python-admin-password','java-admin-initial-password','python-admin-initial-password','mysql-root','mysql-java','mysql-python','mysql-pytest','redis','jwt'):
        p = folder / name
        if p.exists(): values.append(p.read_bytes().strip())
for p in dest.rglob('*'):
    if p.is_file(): assert not any(len(v)>15 and v in p.read_bytes() for v in values), p
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'files':len(manifest), 'sources':len(sources), 'screenshots':summary['screenshots'], 'knownSecretsScan':'passed'}))
