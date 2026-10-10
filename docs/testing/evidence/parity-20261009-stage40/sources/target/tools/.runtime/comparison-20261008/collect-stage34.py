"""Freeze only complete stage34 browser and preservation evidence; never overwrite."""
from pathlib import Path
import ast, hashlib, json, re, shutil
root=Path.cwd(); run=root/'tools/.runtime/comparison-20261008'
dest=root/'docs/testing/evidence/parity-20261009-stage34'
assert not dest.exists(), 'Do not overwrite evidence'
ui=json.loads((run/'stage34-verified-ui-results.json').read_text('utf8'))
assert ui['stats']['expected']==10 and all(ui['stats'][k]==0 for k in ('unexpected','skipped','flaky'))
comparison=json.loads((run/'stage34-comparison.json').read_text('utf8'))
assert len(comparison['auditLayout'])==9 and len(comparison['files'])==4 and len(comparison['realRules'])==2
state=json.loads((run/'stage34-final-state.json').read_text('utf8'))
assert state['backendJarUnchanged'] and state['V1ThroughV35Unchanged'] and state['noActiveTasksOrOwnedFixtures']
unit=json.loads((root/'frontend/test-results/stage34-unit.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests']==78 and unit['numFailedTests']==0
assert 'Total: 185 tests in 24 files' in (run/'stage34-registered-ui.log').read_text('utf8')
secrets=[]; private=root/'deploy/.secrets/comparison-20261008'
for file in private.iterdir():
    if file.is_file() and file.name not in ('init.sql','role-accounts.json'):
        value=file.read_text('utf8').strip()
        if len(value)>8:secrets.append(value)
for system in json.loads((private/'role-accounts.json').read_text()).values():
    for account in system.values():secrets.extend(v for v in account.values() if isinstance(v,str) and len(v)>8)
old=ast.parse((run/'collect-stage28.py').read_text('utf8'))
functions=[n for n in old.body if isinstance(n,ast.FunctionDef) and n.name in ('scrub','copy','tree')]
assert len(functions)==3
exec(compile(ast.Module(body=functions,type_ignores=[]),'reviewed-copy-helpers','exec'))
tree(root/'frontend/test-results/stage34-verified-ui',dest/'ui')
for attempt in ('layout-before','layout-first','layout-second','final-ui'):
    tree(root/f'frontend/test-results/stage34-{attempt}',dest/'diagnostics'/attempt)
    copy(run/f'stage34-{attempt}-results.json',dest/'diagnostics'/f'{attempt}-results.json')
for file in run.glob('stage34-*.log'):copy(file,dest/'diagnostics'/file.name)
for name in ('stage34-verified-ui-results.json','stage34-verified-ui.log','stage34-comparison.json','stage34-final-state.json','stage34-web-checks.log','stage34-format-verified-check.log','stage34-final-static.log','stage34-registered-ui.log','stage34-doc-check.log'):
    copy(run/name,dest/name)
copy(root/'frontend/test-results/stage34-unit.json',dest/'frontend-unit.json')
for name in ('stage34-compare.py','stage34-final-state.py','collect-stage34.py'):copy(run/name,dest/'verification'/name)
sources=set(json.loads((root/'docs/testing/evidence/parity-20261009-stage33/summary.json').read_text('utf8'))['sourceHashes'])
sources.update([
    'frontend/e2e/parity/audit-layout.spec.ts',
    'frontend/src/features/sn/pages/SnAuditPage.tsx',
    'frontend/src/styles.css',
    'frontend/src/components/prototype/icons.json',
    '../sn-center-python/frontend/src/views/Audit.vue',
    '../sn-center-python/frontend/src/style.css',
    '../sn-center-python/frontend/node_modules/element-plus/es/components/table/src/table-layout.mjs',
    '../sn-center-python/frontend/node_modules/element-plus/theme-chalk/src/date-picker/picker.scss',
    '../sn-center-python/frontend/node_modules/@element-plus/icons-vue/dist/index.js',
    'tools/.runtime/comparison-20261008/stage34-compare.py',
    'tools/.runtime/comparison-20261008/stage34-final-state.py',
    'tools/.runtime/comparison-20261008/collect-stage34.py',
])
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
hashes={name:sha(root/name) for name in sorted(sources)}
summary={
    'stage':34,'goalComplete':False,'allCasesRerun':False,'ui':ui['stats'],'workers':1,'retries':0,
    'registeredCases':185,'distinctCasesRun':10,'uiScreenshots':len(list((dest/'ui').rglob('*.png'))),
    'auditLayoutPairs':9,'exactGeometryGroupsPerPair':14,'viewports':[1920,1440,900,390],
    'languages':['zh','en','vi'],'orderedRawTablePairs':15,'exactMainLayoutPairs':15,'exactExpandedDetailCells':20,
    'fullCellValueTypeOrderFilePairs':4,'realRuleFilePairsWith21OrderedColumnsAndAllTypes':2,
    'backendChanged':False,'backendTestsRerun':False,'backendJarUnchanged':True,'frontendUnitTests':78,
    'schema':'35','nextMigration':'36','sourceHashes':hashes,
    'remaining':['P12 calendar popup, all roles, errors/recovery/capacity and full pixel styling','P12 login/platform and other real business audit producers, real failure details and business numbers','P01-P16 full acceptance, P11 remaining boundaries, source page-size race and pre-V31 order','500000-row import performance'],
    'diagnostics':['Before: left pagination, hidden narrow size control, overflowing narrow date range, all wide columns stretched','First: geometry mostly fixed; evidence selector skipped measured-row sibling, narrow separator had minimum width','Second: new arrow padding widened buttons; scroll started during sidebar resize','Initial combined run: 8 pass/2 fail waiting for end scroll before layout stabilized; final test waits actual content width first'],
    'fullPixelEqualityClaimed':False,'warnings':['largeBundle','xlsxDefaultStyleWarning']
}
assert summary['uiScreenshots']==68
(dest/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json','.log','.csv','.py'):
        content=file.read_text('utf-8-sig');assert not any(secret in content for secret in secrets),file.name
manifest={p.relative_to(dest).as_posix():sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'files':len(manifest),'sources':len(hashes),'screenshots':68,'goalComplete':False}))
