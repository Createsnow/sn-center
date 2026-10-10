"""Freeze current UI evidence, source bytes and the separate diagnostic attempts."""
from pathlib import Path
import hashlib,json,shutil

root=Path.cwd();run=root/'tools/.runtime/comparison-20261008'
dest=root/'docs/testing/evidence/parity-20261009-stage52'
assert not (dest/'manifest.json').exists(), 'Never overwrite frozen evidence'
dest.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def copy(p,n):
    t=dest/n;t.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,t)
def tree(folder,prefix):
    for p in folder.rglob('*'):
        if p.is_file():copy(p,Path(prefix)/p.relative_to(folder))

ui={}
for group in ('ui11','focus13'):
    result=json.loads((run/f'stage52-{group}-results.json').read_text('utf8'))
    assert result['stats']['expected']==2
    assert result['stats']['unexpected']==result['stats']['flaky']==result['stats']['skipped']==0
    ui[group]=result['stats'];copy(run/f'stage52-{group}-results.json',Path('ui')/group/'results.json')
    tree(root/'frontend/test-results'/('stage52-'+group),Path('ui')/group/'artifacts')
for group in ('probe1','probe2','ui3','ui4','focus5','ui6','focus7','focus8','ui9','focus10','focus12'):
    tree(root/'frontend/test-results'/('stage52-'+group),Path('diagnostics')/group)
    p=run/f'stage52-{group}-results.json'
    if p.exists():copy(p,Path('diagnostics')/group/'results.json')
copy(run/'stage52-ui4-interrupted.json',Path('diagnostics/ui4/interrupted.json'))
for p in run.glob('stage52-*.log'):copy(p,Path('logs')/p.name)
for group in ('backend','frontend','file','frozen-sql','prototype'):
    copy(run/f'stage52-{group}-hashes.json',Path('checks/baselines')/(group+'-hashes.json'))
for name in ('final-state','dedicated-state','services','ui6-comparison','ui11-comparison','focus5-focus-comparison','focus7-focus-comparison','focus13-focus-comparison'):
    copy(run/f'stage52-{name}.json',Path('checks')/(name+'.json'))
for name in ('compare','focus-compare','final-state','dedicated-check','collect'):
    copy(run/f'stage52-{name}.py',Path('checks')/(name+'.py'))
copy(run/'stage52-stop-dedicated.ps1',Path('checks/stop-dedicated.ps1'))
comparison=json.loads((run/'stage52-ui11-comparison.json').read_text('utf8'))
focus=json.loads((run/'stage52-focus13-focus-comparison.json').read_text('utf8'))
assert not comparison['functionalDifferences']
# 本轮仍发现初始滚动差异：保留失败对照，不能将UI动作通过冒充整体一致。
assert all(v['heightEqual'] and v['controlBoxesEqual'] and v.get('focusTagEqual') is not False for v in focus['pairs'])
units=json.loads((root/'frontend/test-results/stage52-unit.json').read_text('utf8'))
assert units['numPassedTests']==84 and units['numFailedTests']==0
copy(root/'frontend/test-results/stage52-unit.json',Path('checks/frontend-unit.json'))
backend=json.loads((run/'stage52-backend-hashes.json').read_text('utf8'))
assert all(sha(root/n)==v for n,v in backend.items())
jar=sha(root/'backend/adtek-server/target/adtek-server-acceptance.jar')
assert jar==(run/'stage52-jar-sha256.txt').read_text('utf8').strip().lower()
before=json.loads((run/'stage52-frontend-hashes.json').read_text('utf8'))
sources={n for n,v in before.items() if sha(root/n)!=v}
sources.update(['frontend/src/features/sn/components/OrderLineTable.tsx',
                'frontend/e2e/parity/order-focus.spec.ts','frontend/e2e/parity/settleOrderPage.ts',
                'frontend/e2e/parity/order-interactions.spec.ts','frontend/playwright.parity.config.ts',
                'tools/testing/parity-order-audit-fixture.py','tools/testing/parity-k3-fixture.py'])
for n in sorted(sources):copy(root/n,Path('sources')/n)
state=json.loads((run/'stage52-final-state.json').read_text('utf8'))
assert json.loads((run/'stage52-services.json').read_text('utf-8-sig'))['dedicatedPortsClosed']
main_unchanged=all(v.get('allRowsUnchanged',v.get('originalRowsUnchanged',False)) for k in ('java','python') for v in state[k].values())
summary={'date':'2026-10-09','goal':'active','previousGoalTurn':'progress','schema':41,'ui':ui,'frontendUnit':84,
         'backendRerun':False,'backendBytesUnchanged':len(backend),'jarSha256':jar,
         'statePairs':len(comparison['pairs']),'focusStatePairs':len(focus['pairs']),
         'overflowPairs':comparison['overflowPairs'],'shortTextPairs':comparison['shortTextPairs'],
         'scrollClosePairs':comparison['scrollClosesTooltipPairs'],'functionalDifferences':0,
         'focusDifferences':len(focus['differences']),'visualDifferences':len(comparison['visualDifferences']),
         'focusScrollMismatchPairs':sum(not v['scrollEqual'] for v in focus['pairs']),
         'principalBoxMismatchPairs':sum(not v['principalBoxesEqual'] for v in comparison['pairs']),
         'timestampChecks':comparison['timestampChecksAgainstOwnDatabase'],
         'screenshots':len(list((dest/'ui').rglob('*.png'))),
         'diagnosticScreenshots':len(list((dest/'diagnostics').rglob('*.png'))),
         'sourceFiles':{n:sha(root/n) for n in sorted(sources)},
         'registeredUiTests':231,'registeredUiFiles':42,'fullUiMatrixRerun':False,
         'mainDataAllUnchanged':main_unchanged,
         'originalFilesPreserved':state['file']['files'],'prototypeFilesPreserved':state['prototype']['files'],
         'frozenSqlPreserved':state['frozen-sql']['files'],'dedicatedServicesStopped':True,
         'remaining':['Initial-load/full-sync overlap and remaining order details/visuals',
                      'Complete P01-P16 role/language/viewport UI matrix',
                      'K3 malformed/oversized/coercion, generation concurrency, recovery, MES and end-to-end UI business loop']}
(dest/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf8')
private=root/'deploy/.secrets/comparison-20261008';values=[]
for folder in (private,private/'stage47',private/'stage48'):
    for name in ('java-admin-password','python-admin-password','java-admin-initial-password','python-admin-initial-password','mysql-root','mysql-java','mysql-python','mysql-pytest','redis','jwt'):
        p=folder/name
        if p.exists():values.append(p.read_bytes().strip())
for p in dest.rglob('*'):
    if p.is_file():assert not any(len(v)>15 and v in p.read_bytes() for v in values),p
manifest={p.relative_to(dest).as_posix():sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'files':len(manifest),'sources':len(sources),'screenshots':summary['screenshots'],'diagnosticScreenshots':summary['diagnosticScreenshots'],'knownSecretsScan':'passed'}))
