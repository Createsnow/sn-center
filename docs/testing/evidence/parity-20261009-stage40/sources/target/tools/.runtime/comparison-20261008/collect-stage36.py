"""Freeze the complete local login comparison once, preserving prior evidence."""
from pathlib import Path
import ast, hashlib, json, re, shutil

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
dest = root / 'docs/testing/evidence/parity-20261009-stage36'
assert not dest.exists(), 'Never overwrite frozen evidence'
ui = json.loads((run / 'stage36-final-ui-results.json').read_text('utf8'))
assert ui['stats']['expected'] == 8
assert all(ui['stats'][key] == 0 for key in ('unexpected', 'skipped', 'flaky'))
layout = json.loads((run / 'stage36-verified-layout-results.json').read_text('utf8'))
assert layout['stats']['expected'] == 2 and all(layout['stats'][key] == 0 for key in ('unexpected', 'skipped', 'flaky'))
comparison = json.loads((run / 'stage36-comparison.json').read_text('utf8'))
assert len(comparison['loginLayout']) == 18 and len(comparison['authentication']) == 4
assert all(item['groupCount'] == 15 for item in comparison['loginLayout'])
state = json.loads((run / 'stage36-final-state.json').read_text('utf8'))
assert state['backendSourcesAndAcceptanceJarUnchanged'] and state['noActiveTasksOrOwnedFixtures']
unit = json.loads((root / 'frontend/test-results/stage36-unit.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests'] == 78 and unit['numFailedTests'] == 0
assert 'Total: 193 tests in 26 files' in (run / 'stage36-registered-ui.log').read_text('utf8')
private = root / 'deploy/.secrets/comparison-20261008'
secrets = []
for file in private.iterdir():
    if file.is_file() and file.suffix != '.json' and file.name != 'init.sql':
        value = file.read_text('utf8').strip()
        if len(value) > 8:
            secrets.append(value)
for system in json.loads((private / 'role-accounts.json').read_text('utf8')).values():
    for account in system.values():
        secrets.extend(v for v in account.values() if isinstance(v, str) and len(v) > 8)
for file in private.glob('auth-fixture-*.json'):
    for entry in json.loads(file.read_text('utf8'))['entries'].values():
        secrets.extend([entry['initial'], entry['changed']])
old = ast.parse((run / 'collect-stage28.py').read_text('utf8'))
functions = [n for n in old.body if isinstance(n, ast.FunctionDef) and n.name in ('scrub', 'copy', 'tree')]
assert len(functions) == 3
exec(compile(ast.Module(body=functions, type_ignores=[]), 'reviewed-copy-helpers', 'exec'))
for directory in (root / 'frontend/test-results/stage36-final-ui').glob('audit-auth-*'):
    tree(directory, dest / 'ui' / directory.name)
tree(root / 'frontend/test-results/stage36-verified-layout', dest / 'ui')
for directory in (root / 'frontend/test-results/stage36-final-ui').glob('login-layout-*'):
    tree(directory, dest / 'diagnostics' / 'combined-login-layout' / directory.name)
for attempt in ('before-ui', 'layout1', 'layout2', 'layout3', 'layout4'):
    tree(root / f'frontend/test-results/stage36-{attempt}', dest / 'diagnostics' / attempt)
    copy(run / f'stage36-{attempt}-results.json', dest / 'diagnostics' / f'{attempt}-results.json')
for file in run.glob('stage36-*.log'):
    copy(file, dest / 'logs' / file.name)
for name in ('stage36-final-ui-results.json', 'stage36-verified-layout-results.json', 'stage36-combined-source-hashes.json', 'stage36-comparison.json', 'stage36-final-state.json'):
    copy(run / name, dest / name)
copy(root / 'frontend/test-results/stage36-unit.json', dest / 'frontend-unit.json')
for file in run.glob('stage35-clean-*-1009360001.json'):
    copy(file, dest / 'cleanup' / file.name)
for name in ('stage36-compare.py', 'stage36-final-state.py', 'collect-stage36.py'):
    copy(run / name, dest / 'verification' / name)
sources = set(json.loads((root / 'docs/testing/evidence/parity-20261009-stage35/summary.json').read_text('utf8'))['sourceHashes'])
sources.update([
    'frontend/e2e/parity/login-layout.spec.ts',
    'frontend/src/features/auth/pages/LoginPage.tsx',
    'frontend/src/components/LanguageSelect.tsx',
    'frontend/src/components/prototype/icons.json',
    'frontend/src/styles.css',
])
sources.update('tools/.runtime/comparison-20261008/' + name for name in ('stage36-compare.py', 'stage36-final-state.py', 'collect-stage36.py'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
summary = {
    'stage': 36, 'goalComplete': False, 'allCasesRerun': False,
    'ui': ui['stats'], 'layoutRecheck': layout['stats'], 'workers': 1, 'retries': 0, 'registeredCases': 193,
    'distinctCasesRun': 8, 'uiScreenshots': len(list((dest / 'ui').rglob('*.png'))),
    'loginStatesCompared': 18, 'loginGeometryStyleIconGroupsPerState': 15,
    'languageWidthCombinations': 12, 'languages': ['zh-CN', 'en', 'vi'],
    'widths': [1920, 1440, 900, 390], 'frontendUnitTests': 78,
    'backendSuiteRerun': False, 'backendAndJarUnchanged': True,
    'schema': '36', 'nextMigration': '37',
    'authenticationFilePairs': 4, 'authenticationFactsPerSide': 14,
    'authenticationOrderedMatchingColumns': 22,
    'fullPixelEqualityClaimed': False,
    'sourceHashes': {name: sha(root / name) for name in sorted(sources)},
    'remaining': [
        'P02 loading/disabled/error/redirect/length boundaries and language dropdown full appearance',
        'P03 complete password form appearance and validation',
        'P12 other real producers/management, permissions, recovery and capacity',
        'P13/P14 accounts and factories, all remaining P01-P16 requirements',
        'Source page-size race, pre-V31 ordering, 500000-row import performance',
    ],
    'diagnostics': [
        'Initial UI: prototype passes; migration adds empty-submit validation; font/icon/geometry differences measured',
        'First correction: empty password suffix and label pseudo-element retained extra width',
        'Full outer-control equality still hid language input color and arrow size; expanded measurements and corrected styles',
        'Combined eight cases passed; language text retained 6px extra content margin, fixed with two focused layout rechecks; only this CSS margin differs from combined-run source hash',
        'Final exact comparisons retain actual operation time and request IDs; full pixel equality remains unproven',
    ],
}
(dest / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json', '.log', '.csv', '.py', '.md'):
        assert not any(value in file.read_text('utf-8-sig') for value in secrets), file.name
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'files': len(manifest), 'sources': len(sources), 'screenshots': summary['uiScreenshots'], 'goalComplete': False}))
