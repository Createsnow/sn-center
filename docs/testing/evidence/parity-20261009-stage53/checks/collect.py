"""Freeze the focused functionality checks and retain failed diagnostic attempts honestly."""
from pathlib import Path
import hashlib,json,shutil
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';dest=root/'docs/testing/evidence/parity-20261009-stage53'
assert not (dest/'manifest.json').exists()
dest.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def copy(p,n):
    t=dest/n;t.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,t)
def tree(folder,prefix):
    for p in folder.rglob('*'):
        if p.is_file():copy(p,Path(prefix)/p.relative_to(folder))
stats={}
for group in ('revisit-after','ui-final','loop-focus-final','business-final2'):
    result=json.loads((run/f'stage53-{group}-results.json').read_text('utf8'))
    stats[group]=result['stats']
    if group in ('revisit-after','business-final2'):
        assert result['stats']['expected']==2 and result['stats']['unexpected']==0
    tree(root/'frontend/test-results'/('stage53-'+group),Path('runs')/group/'artifacts')
    copy(run/f'stage53-{group}-results.json',Path('runs')/group/'results.json')
for group in ('probe1','probe2','probe3','revisit-before','loop-focus','business-final'):
    tree(root/'frontend/test-results'/('stage53-'+group),Path('diagnostics')/group)
    p=run/f'stage53-{group}-results.json'
    if p.exists():copy(p,Path('diagnostics')/group/'results.json')
for p in run.glob('stage53-*.log'):copy(p,Path('logs')/p.name)
for p in run.glob('stage53-*-source.ts'):copy(p,Path('diagnostics/sources')/p.name)
for name in ('ui-final-comparison','loop-focus-final-focus-comparison','extra-comparison','business-comparison','final-state','dedicated-state','services'):
    copy(run/f'stage53-{name}.json',Path('checks')/(name+'.json'))
for name in ('compare','focus-compare','extra-compare','loop-compare','final-state','dedicated-check','collect'):
    copy(run/f'stage53-{name}.py',Path('checks')/(name+'.py'))
for group in ('frontend','backend','prototype','file','frozen-sql'):
    copy(run/f'stage53-{group}-hashes.json',Path('checks/baselines')/(group+'.json'))
before=json.loads((run/'stage53-frontend-hashes.json').read_text('utf8'))
sources={n for n,v in before.items() if sha(root/n)!=v}
sources.update('frontend/e2e/parity/'+n for n in ('business-loop.spec.ts','order-focus.spec.ts','order-interactions.spec.ts','order-revisit.spec.ts','order-scroll.spec.ts','clickVisibleControl.ts','settleOrderPage.ts'))
sources.update(('frontend/playwright.parity.config.ts','tools/testing/parity-order-audit-fixture.py'))
for n in sorted(sources):copy(root/n,Path('sources')/n)
backend=json.loads((run/'stage53-backend-hashes.json').read_text('utf8'))
assert all(sha(root/n)==v for n,v in backend.items())
assert sha(root/'backend/adtek-server/target/adtek-server-acceptance.jar')==(run/'stage53-jar-sha256.txt').read_text('utf8').strip().lower()
units=json.loads((root/'frontend/test-results/stage53-unit.json').read_text('utf8'));assert units['numPassedTests']==84 and units['numFailedTests']==0
copy(root/'frontend/test-results/stage53-unit.json',Path('checks/frontend-unit.json'))
focus=json.loads((run/'stage53-loop-focus-final-focus-comparison.json').read_text('utf8'));assert not focus['differences']
business=json.loads((run/'stage53-business-comparison.json').read_text('utf8'))
state=json.loads((run/'stage53-final-state.json').read_text('utf8'))
assert json.loads((run/'stage53-services.json').read_text('utf-8-sig'))['dedicatedPortsClosed']
summary={'date':'2026-10-09','goal':'active','previousGoalTurn':'progress','schema':41,
 'userPriority':'Finish functional gaps and complete UI workflow; minor visual differences no longer block acceptance.',
 'runs':stats,'note':'ui-final includes one failed rapid keyboard-step attempt; loop-focus-final includes two failed first-query observation attempts. Their logs remain failures. Focus then passes on both systems; business-final is the corrected final paired workflow.',
 'frontendUnit':84,'backendRerun':False,'backendBytesUnchanged':len(backend),'jarSha256':state['jarSha256'],
 'sourceFiles':{n:sha(root/n) for n in sorted(sources)},'focusPairs':len(focus['pairs']),
 'businessStatePairs':len(business['statePairs']),'businessXlsxFiles':4,'businessAuditFactsPerSystem':7,
 'screenshots':len(list((dest/'runs').rglob('*.png'))),'diagnosticScreenshots':len(list((dest/'diagnostics').rglob('*.png'))),
 'mainDatabaseChanges':{k:{t:v for t,v in state[k].items() if not v.get('allRowsUnchanged',v.get('originalRowsUnchanged',False))} for k in ('java','python')},
 'originalFilesPreserved':state['file']['files'],'prototypeFilesPreserved':state['prototype']['files'],'frozenSqlPreserved':state['frozen-sql']['files'],
 'dedicatedServicesStopped':True,'fullUiMatrixRerun':False,
 'remaining':['Review P01-P16 functional evidence using the latest user priority; stop expanding cosmetic matrices.','P16 MES and remaining concrete functional differences; production/company-network integration remains outside local acceptance.']}
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
print(json.dumps({'files':len(manifest),'sources':len(sources),'screenshots':summary['screenshots'],'knownSecretsScan':'passed'}))
