"""Freeze the local password, login and authentication comparison once, preserving prior evidence."""
from pathlib import Path
import ast, hashlib, json, re, shutil

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
dest = root / 'docs/testing/evidence/parity-20261009-stage37'
assert not dest.exists(), 'Never overwrite frozen evidence'
ui = json.loads((run / 'stage37-final-ui-results.json').read_text('utf8'))
assert ui['stats']['expected'] == 14
assert all(ui['stats'][key] == 0 for key in ('unexpected', 'skipped', 'flaky'))
comparison = json.loads((run / 'stage37-comparison.json').read_text('utf8'))
assert len(comparison['loginLayout']) == 18 and len(comparison['authentication']) == 4
assert all(item['groupCount'] == 15 for item in comparison['loginLayout'])
assert len(comparison['passwordPages']) == 33 and len(comparison['passwordActions']) == len(comparison['passwordSuccess']) == 3
state = json.loads((run / 'stage37-final-state.json').read_text('utf8'))
assert state['backendSourcesAndAcceptanceJarUnchanged'] and state['noActiveTasksOrOwnedFixtures']
unit = json.loads((root / 'frontend/test-results/stage37-unit.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests'] == 78 and unit['numFailedTests'] == 0
assert 'Total: 199 tests in 27 files' in (run / 'stage37-registered-ui.log').read_text('utf8')
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
tree(root / 'frontend/test-results/stage37-final-ui', dest / 'ui')
for attempt in ('before','layout1','layout2','layout3'):
    tree(root / f'frontend/test-results/stage37-{attempt}', dest / 'diagnostics' / attempt)
    name = 'stage37-before-results.json' if attempt == 'before' else f'stage37-{attempt}-results.json'
    copy(run / name, dest / 'diagnostics' / name)
for file in run.glob('stage37-*.log'):
    copy(file, dest / 'logs' / file.name)
for name in ('stage37-final-ui-results.json', 'stage37-comparison.json', 'stage37-final-state.json'):
    copy(run / name, dest / name)
copy(root / 'frontend/test-results/stage37-unit.json', dest / 'frontend-unit.json')
for file in run.glob('stage35-clean-*-100937*.json'):
    copy(file, dest / 'cleanup' / file.name)
for name in ('stage37-compare.py', 'stage37-final-state.py', 'collect-stage37.py'):
    copy(run / name, dest / 'verification' / name)
sources = set(json.loads((root / 'docs/testing/evidence/parity-20261009-stage36-drawn-text/summary.json').read_text('utf8'))['sourceHashes'])
sources.update([
    'frontend/e2e/parity/login-layout.spec.ts',
    'frontend/e2e/parity/password-page.spec.ts',
    'frontend/src/features/auth/pages/PasswordPage.tsx',
    'tools/testing/parity-auth-fixture.py',
    'frontend/src/features/auth/pages/LoginPage.tsx',
    'frontend/src/components/LanguageSelect.tsx',
    'frontend/src/components/prototype/icons.json',
    'frontend/src/styles.css',
])
sources.update('tools/.runtime/comparison-20261008/' + name for name in ('stage37-compare.py', 'stage37-final-state.py', 'collect-stage37.py'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
summary = {
    'stage': 37, 'goalComplete': False, 'allCasesRerun': False,
    'ui': ui['stats'], 'workers': 1, 'retries': 0, 'registeredCases': 199,
    'distinctCasesRun': 14, 'uiScreenshots': len(list((dest / 'ui').rglob('*.png'))),
    'passwordForcedWidths': [1440, 900, 390], 'passwordVoluntaryWidths': [1440, 390],
    'passwordPagePairs':33, 'passwordGeometryGroupsPerPair':15, 'passwordSuccessMessagePairs':3, 'passwordLanguageActionFlows':3,
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
        'P03 pending/disabled/network/length/Unicode/control-character boundaries and all remaining states',
        'P04 observed English shortcut wrapping and header identity geometry differ; accumulated daily counts also differ and require source-fact review',
        'P12 other real producers/management, permissions, recovery and capacity',
        'P13/P14 accounts and factories, all remaining P01-P16 requirements',
        'Source page-size race, pre-V31 ordering, 500000-row import performance',
    ],
    'diagnostics': [
        'Initial prototype baseline timed out on a wrong account menu selector; corrected. Migration baseline confirms validation occurs while typing unlike source blur behavior.',
        'First correction: warning icon and padding, error positioning/min-height offset and error border needed adjustment.',
        'Three-language review: extra 8px warning right padding caused different wrapping, including an extra English line at 390px; fixed and character rectangles added.',
        'Global success message inherited a different font and notification shadow padding; corrected scoped font, 14px icon, line height and notification offset.',
    ],
}
(dest / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json', '.log', '.csv', '.py', '.md'):
        assert not any(value in file.read_text('utf-8-sig') for value in secrets), file.name
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'files': len(manifest), 'sources': len(sources), 'screenshots': summary['uiScreenshots'], 'goalComplete': False}))
