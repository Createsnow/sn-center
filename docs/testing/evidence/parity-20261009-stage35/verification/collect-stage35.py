"""Freeze only complete stage35 browser and preservation evidence; never overwrite."""
from pathlib import Path
import ast, hashlib, json, re, shutil
root=Path.cwd(); run=root/'tools/.runtime/comparison-20261008'
dest=root/'docs/testing/evidence/parity-20261009-stage35'
assert not dest.exists(), 'Do not overwrite evidence'
ui=json.loads((run/'stage35-verified-ui-results.json').read_text('utf8'))
assert ui['stats']['expected']==16 and all(ui['stats'][k]==0 for k in ('unexpected','skipped','flaky'))
comparison=json.loads((run/'stage35-comparison.json').read_text('utf8'))
assert len(comparison['auditLayout'])==9 and len(comparison['files'])==4 and len(comparison['realRules'])==2 and len(comparison['authentication'])==4
state=json.loads((run/'stage35-final-state.json').read_text('utf8'))
assert state['java']['schema']=='36' and state['V1ThroughV35Unchanged'] and state['noActiveTasksOrOwnedFixtures']
unit=json.loads((root/'frontend/test-results/stage35-unit-final.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests']==78 and unit['numFailedTests']==0
assert 'Total: 191 tests in 25 files' in (run/'stage35-registered-ui.log').read_text('utf8')
secrets=[]; private=root/'deploy/.secrets/comparison-20261008'
for file in private.iterdir():
    if file.is_file() and file.name not in ('init.sql','role-accounts.json'):
        value=file.read_text('utf8').strip()
        if len(value)>8:secrets.append(value)
for system in json.loads((private/'role-accounts.json').read_text()).values():
    for account in system.values():secrets.extend(v for v in account.values() if isinstance(v,str) and len(v)>8)
for file in private.glob('auth-fixture-*.json'):
    fixture=json.loads(file.read_text('utf8'))
    for entry in fixture['entries'].values():secrets.extend([entry['initial'],entry['changed']])
old=ast.parse((run/'collect-stage28.py').read_text('utf8'))
functions=[n for n in old.body if isinstance(n,ast.FunctionDef) and n.name in ('scrub','copy','tree')]
assert len(functions)==3
exec(compile(ast.Module(body=functions,type_ignores=[]),'reviewed-copy-helpers','exec'))
tree(root/'frontend/test-results/stage35-verified-ui',dest/'ui')
for attempt in ('before-ui','auth-ui','final-ui'):
    tree(root/f'frontend/test-results/stage35-{attempt}',dest/'diagnostics'/attempt)
    copy(run/f'stage35-{attempt}-results.json',dest/'diagnostics'/f'{attempt}-results.json')
for file in run.glob('stage35-*.log'):copy(file,dest/'diagnostics'/file.name)
for name in ('stage35-verified-ui-results.json','stage35-verified-ui.log','stage35-comparison.json','stage35-final-state.json','stage35-backend-results.json','stage35-registered-ui.log','stage35-doc-check.log','stage35-before-real-login.json'):
    copy(run/name,dest/name)
copy(root/'frontend/test-results/stage35-unit-final.json',dest/'frontend-unit.json')
copy(root/'frontend/test-results/stage35-unit.json',dest/'diagnostics'/'frontend-unit-initial.json')
for file in run.glob('stage35-clean-*.json'):copy(file,dest/'cleanup'/file.name)
for name in ('stage35-compare.py','stage35-final-state.py','collect-stage35.py'):copy(run/name,dest/'verification'/name)
backend=json.loads((run/'stage35-backend-results.json').read_text('utf8'))
assert backend['surefire']['tests']==88 and backend['failsafe']['tests']==82
import subprocess
sources=set(json.loads((root/'docs/testing/evidence/parity-20261009-stage34/summary.json').read_text('utf8'))['sourceHashes'])
for args in (['diff','--name-only','--','backend','frontend','tools/testing'],['ls-files','--others','--exclude-standard','--','backend','frontend','tools/testing']):
    for name in subprocess.check_output(['git','-c','core.quotepath=false']+args,text=True,encoding='utf8').splitlines():
        if Path(name).suffix in ('.java','.ts','.tsx','.json','.sql','.py','.css','.yml') and (root/name).is_file():sources.add(name)
sources.update(['../sn-center-python/app/services/users.py','../sn-center-python/app/api/routes/auth.py','../sn-center-python/app/core/errors.py','../sn-center-python/frontend/src/views/Login.vue','../sn-center-python/frontend/src/views/ChangePassword.vue'])
sources.update('tools/.runtime/comparison-20261008/'+name for name in ('stage35-compare.py','stage35-final-state.py','collect-stage35.py'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
hashes={name:sha(root/name) for name in sorted(sources)}
summary={'stage':35,'goalComplete':False,'allCasesRerun':False,'ui':ui['stats'],'workers':1,'retries':0,
'registeredCases':191,'distinctCasesRun':16,'uiScreenshots':len(list((dest/'ui').rglob('*.png'))),
'authenticationFilePairs':4,'authenticationFactsPerSide':14,'authenticationOrderedMatchingColumns':22,
'authenticationActorRowsWithEqualCharacterRectangles':14,'paintedMessagePairs':2,
'fullCellValueTypeOrderFilePairs':4,'realRuleFilePairsWith21OrderedColumnsAndAllTypes':2,
'auditLayoutPairs':9,'exactGeometryGroupsPerPair':14,'backendUnitTests':88,'backendIntegrationTests':82,'frontendUnitTests':78,
'schema':'36','nextMigration':'37','newAuditExportFormat':6,'oldAuditFormatsSha256Regenerated':[1,2,3,4,5],
'sourceHashes':hashes,'fullPixelEqualityClaimed':False,
'remaining':['P12 user-management and other real producers, all permission/error/recovery/capacity checks','P02/P03 remaining validation/redirect/locale/responsive/style combinations','P01-P16 full acceptance, source page-size race and pre-V31 ordering','500000-row import performance'],
'diagnostics':['Initial UI: prototype passes, target lacks equivalent login error text; database confirms missing real LOGIN audit','First backend run: nullable lock duration accidentally unboxed; fixed; legacy digest test updated to exclude both optional extensions','First auth suite: retained collapsed details collide in strict text locator, cleanup used wrong sys_task identity column; real audits retained, original accounts protected and exact owned cleanup later completed','Frontend legacy translation consistency rejected replacing frozen mappings; kept historic mapping and added explicit authentication-page prototype translations','Unused username prop removal corrected before final UI run','Initial combined 16 cases passed; screenshot review found long actor overflow and prototype error captured during entry animation. Actor wrapping corrected, unknown/locked messages require full opacity before and after capture; final 16 cases rerun. Login input/icon/font and remaining detail styling differences remain documented.']}
(dest/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json','.log','.csv','.py'):
        content=file.read_text('utf-8-sig');assert not any(secret in content for secret in secrets),file.name
manifest={p.relative_to(dest).as_posix():sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'files':len(manifest),'sources':len(hashes),'screenshots':summary['uiScreenshots'],'goalComplete':False}))
