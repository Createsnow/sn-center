"""Freeze the dashboard/header comparison and related regressions once, preserving prior evidence."""
from pathlib import Path
import ast, hashlib, json, re, shutil

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
dest = root / 'docs/testing/evidence/parity-20261009-stage38'
assert not dest.exists(), 'Never overwrite frozen evidence'
ui = json.loads((run / 'stage38-final-ui-results.json').read_text('utf8'))
assert ui['stats']['expected'] == 20
assert all(ui['stats'][key] == 0 for key in ('unexpected', 'skipped', 'flaky'))
narrow = json.loads((run/'stage38-narrow-delivery-results.json').read_text('utf8'))['stats']
assert narrow['expected'] == 2 and all(narrow[key] == 0 for key in ('unexpected', 'skipped', 'flaky'))
comparison = json.loads((run / 'stage38-comparison.json').read_text('utf8'))
assert len(comparison['loginLayout']) == 18 and len(comparison['dashboard']) == 15
assert all(item['groupCount'] == 15 for item in comparison['loginLayout'])
assert len(comparison['passwordPages']) == 33 and len(comparison['passwordActions']) == len(comparison['passwordSuccess']) == 3
state = json.loads((run / 'stage38-final-state.json').read_text('utf8'))
assert state['backendSourcesAndAcceptanceJarUnchanged'] and state['noActiveTasksOrOwnedFixtures']
unit = json.loads((root / 'frontend/test-results/stage38-unit.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests'] == 78 and unit['numFailedTests'] == 0
assert 'Total: 201 tests in 28 files' in (run / 'stage38-registered-ui.log').read_text('utf8')
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
tree(root / 'frontend/test-results/stage38-final-ui', dest / 'ui')
tree(root / 'frontend/test-results/stage38-narrow-delivery', dest / 'narrow-ui')
if (run/'stage38-narrow-before-results.json').exists():
    tree(root/'frontend/test-results/stage38-narrow-ui', dest/'diagnostics/narrow-before')
    copy(run/'stage38-narrow-before-results.json', dest/'diagnostics/stage38-narrow-before-results.json')
tree(root/'frontend/test-results/stage38-narrow-final-ui', dest/'diagnostics/narrow-browser-back')
copy(run/'stage38-narrow-final-ui-results.json', dest/'diagnostics/stage38-narrow-final-ui-results.json')
for attempt in ('before','layout1','layout2','layout3'):
    tree(root / f'frontend/test-results/stage38-{attempt}', dest / 'diagnostics' / attempt)
    name = 'stage38-before-results.json' if attempt == 'before' else f'stage38-{attempt}-results.json'
    copy(run / name, dest / 'diagnostics' / name)
for file in run.glob('stage38-*.log'):
    copy(file, dest / 'logs' / file.name)
for name in ('stage38-final-ui-results.json', 'stage38-comparison.json', 'stage38-final-state.json', 'stage38-statistics.json', 'stage38-narrow-delivery-results.json'):
    copy(run / name, dest / name)
copy(root / 'frontend/test-results/stage38-unit.json', dest / 'frontend-unit.json')
for file in run.glob('stage35-clean-*-100938*.json'):
    copy(file, dest / 'cleanup' / file.name)
for name in ('stage38-compare.py', 'stage38-final-state.py', 'collect-stage38.py', 'stage38-statistics.py'):
    copy(run / name, dest / 'verification' / name)
sources = set(json.loads((root / 'docs/testing/evidence/parity-20261009-stage37/summary.json').read_text('utf8'))['sourceHashes'])
sources.update([
    'frontend/e2e/parity/dashboard-layout.spec.ts',
    'frontend/e2e/parity/dashboard-roles.spec.ts',
    'frontend/src/app/WorkspaceShell.tsx',
    'frontend/e2e/parity/login-layout.spec.ts',
    'frontend/e2e/parity/password-page.spec.ts',
    'frontend/src/features/auth/pages/PasswordPage.tsx',
    'tools/testing/parity-auth-fixture.py',
    'frontend/src/features/auth/pages/LoginPage.tsx',
    'frontend/src/components/LanguageSelect.tsx',
    'frontend/src/components/prototype/icons.json',
    'frontend/src/styles.css',
])
sources.update('tools/.runtime/comparison-20261008/' + name for name in ('stage38-compare.py', 'stage38-final-state.py', 'collect-stage38.py', 'stage38-statistics.py'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
summary = {
    'stage': 38, 'goalComplete': False, 'allCasesRerun': False,
    'ui': ui['stats'], 'workers': 1, 'retries': 0, 'registeredCases': 201,
    'distinctCasesRun': 20, 'uiScreenshots': len(list((dest / 'ui').rglob('*.png'))),
    'narrowRecheckScreenshots': len(list((dest / 'narrow-ui').rglob('*.png'))),
    'dashboardPairs': 15, 'dashboardGroupsPerPair': 29,
    'mainRunBeforeFinalTableBorderStyle': True,
    'narrowRecheck': json.loads((run/'stage38-narrow-delivery-results.json').read_text('utf8'))['stats'],
    'passwordForcedWidths': [1440, 900, 390], 'passwordVoluntaryWidths': [1440, 390],
    'passwordPagePairs':33, 'passwordGeometryGroupsPerPair':15, 'passwordSuccessMessagePairs':3, 'passwordLanguageActionFlows':3,
    'loginStatesCompared': 18, 'loginGeometryStyleIconGroupsPerState': 15,
    'languageWidthCombinations': 12, 'languages': ['zh-CN', 'en', 'vi'],
    'widths': [1920, 1440, 900, 390], 'frontendUnitTests': 78,
    'backendSuiteRerun': False, 'backendAndJarUnchanged': True,
    'schema': '36', 'nextMigration': '37',
    'authenticationAuditSuiteRerun': False,
    'statisticsCompareOwnStoredFacts': True,
    'fullPixelEqualityClaimed': False,
    'sourceHashes': {name: sha(root / name) for name in sorted(sources)},
    'remaining': [
        'P02 loading/disabled/error/redirect/length boundaries and language dropdown full appearance',
        'P03 pending/disabled/network/length/Unicode/control-character boundaries and all remaining states',
        'P01 collapsed menu accessible names differ (source 0, target 1); first-icon click verified in both',
        'P04 other roles full measured appearance, table full layout, pending/failed jobs and warnings; future-date query boundary differs (source lacks upper bound)',
        'P12 other real producers/management, permissions, recovery and capacity',
        'P13/P14 accounts and factories, all remaining P01-P16 requirements',
        'Source page-size race, pre-V31 ordering, 500000-row import performance',
    ],
    'diagnostics': ['Initial captures passed navigation but geometry revealed button wrapping, header/font/tag and narrow page spacing differences. Three follow-up measurements retained.', 'First narrow follow-up timed out on the source after scrolling; a closed-page cleanup error masked the exact waiting locator. Later checks verified browser Back and direct first-icon menu return; collapsed-menu accessible-name counts remain independently recorded (source 0, target 1).', 'Narrow screenshot initially omitted the last shortcut below the internal scroll fold; final recheck scrolls to and clicks it in every language.'],
}
(dest / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json', '.log', '.csv', '.py', '.md'):
        assert not any(value in file.read_text('utf-8-sig') for value in secrets), file.name
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'files': len(manifest), 'sources': len(sources), 'screenshots': summary['uiScreenshots'], 'goalComplete': False}))
