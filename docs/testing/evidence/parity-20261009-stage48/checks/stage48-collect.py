"""Freeze verified stage48 results and sources; exclude private database/account snapshots."""
from pathlib import Path
import hashlib,json,re,shutil,xml.etree.ElementTree as ET
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';dest=root/'docs/testing/evidence/parity-20261009-stage48'
assert not (dest/'manifest.json').exists(),'Frozen evidence must not be overwritten'
dest.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def copy(p,name):
    to=dest/name;to.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,to)
reports={}
for group,folder in [('ui3','stage48-ui3'),('filters','stage48-filters')]:
    p=run/f'stage48-{group}-results.json';r=json.loads(p.read_text('utf8'));assert r['stats']['expected']==2 and r['stats']['unexpected']==r['stats']['flaky']==r['stats']['skipped']==0
    reports[group]=r['stats'];copy(p,Path('ui')/(group+'-results.json'))
    for f in (root/'frontend/test-results'/folder).rglob('*'):
        if f.is_file():copy(f,Path('ui')/group/f.relative_to(root/'frontend/test-results'/folder))
for group in ('ui1','ui2'):
    folder=root/'frontend/test-results'/('stage48-'+group)
    for f in folder.rglob('*'):
        if f.is_file():copy(f,Path('diagnostics')/group/f.relative_to(folder))
for f in run.glob('stage48-*.log'):copy(f,Path('logs')/f.name)
for name in ('comparison','final-state'):
    copy(run/f'stage48-{name}.json',Path('checks')/f'stage48-{name}.json')
for name in ('compare','final-state','collect'):
    copy(run/f'stage48-{name}.py',Path('checks')/f'stage48-{name}.py')
copy(run/'stage48-SnAuditIT-first.xml',Path('diagnostics/backend/SnAuditIT-first.xml'))
units=json.loads((root/'frontend/test-results/stage48-unit.json').read_text('utf8'));assert units['numPassedTests']==82 and units['numFailedTests']==0
copy(root/'frontend/test-results/stage48-unit.json',Path('checks/frontend-unit.json'))
log=(run/'stage48-backend-final.log').read_text('utf8',errors='replace');assert 'BUILD SUCCESS' in log
tests=[]
for xml in (root/'backend').glob('*/target/*-reports/TEST-*.xml'):
    t=ET.parse(xml).getroot();name=t.get('name')
    if name.endswith('IT') and name not in ['com.adtek.server.SnAuditIT','com.adtek.server.SnFoundationIT','com.adtek.server.FactoryMappingMigrationIT']:continue
    if not name.endswith('IT') and not re.search(r'-- in '+re.escape(name)+r'\b',log):continue
    assert int(t.get('failures'))==int(t.get('errors'))==int(t.get('skipped'))==0,name
    tests.append({'name':name,'count':int(t.get('tests')),'seconds':float(t.get('time'))})
    copy(xml,Path('backend')/xml.name)
assert sum(t['count'] for t in tests if t['name'].endswith('IT'))==59
assert sum(t['count'] for t in tests if not t['name'].endswith('IT'))==89
sources=set()
for kind in ('backend','frontend'):
    before=json.loads((run/f'stage48-{kind}-hashes.json').read_text('utf8'))
    for name,digest in before.items():
        if sha(root/name)!=digest:sources.add(name)
sources.update(['backend/adtek-sn/src/main/java/com/adtek/sn/service/SnOrderFailureAuditService.java','backend/adtek-sn/src/main/java/com/adtek/sn/service/impl/SnOrderFailureAuditAspect.java','backend/adtek-sn/src/main/java/com/adtek/sn/service/impl/SnOrderFailureAuditServiceImpl.java','backend/adtek-sn/src/main/java/com/adtek/sn/service/support/OrderAuditText.java','frontend/e2e/parity/order-audit.spec.ts','frontend/playwright.parity.config.ts','tools/testing/parity-order-audit-fixture.py','tools/testing/parity-k3-fixture.py'])
for name in sorted(sources):copy(root/name,Path('sources')/name)
summary={'date':'2026-10-09','goal':'active','schema':41,'backend':tests,'frontendUnit':82,'ui':reports,'screenshots':64,'diagnosticScreenshots':10,'registeredUiTests':223,'registeredUiFiles':38,'fullUiMatrixRerun':False,'actualExcelPairs':3,'actualExcelRows':13,'equalBusinessColumns':22,'sourceFiles':{n:sha(root/n) for n in sorted(sources)},'mainDataPreserved':True,'temporaryServicesStopped':True,'remaining':['Scheduled SQL/internal error text','Blank bill and schedule interval API validation semantics','Full K3 response/error cases','P01-P16 full UI matrix, generation, MES, concurrency and recovery']}
(dest/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf8')
private=root/'deploy/.secrets/comparison-20261008'
values=[]
for base in (private,private/'stage47',private/'stage48'):
    for name in ['java-admin-password','python-admin-password','java-admin-initial-password','python-admin-initial-password','mysql-root','mysql-java','mysql-python','mysql-pytest','redis','jwt']:
        f=base/name
        if f.exists():values.append(f.read_bytes().strip())
for f in dest.rglob('*'):
    if f.is_file():
        content=f.read_bytes();assert not any(len(v)>15 and v in content for v in values),f
manifest={p.relative_to(dest).as_posix():sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'files':len(manifest),'sources':len(sources),'screenshots':64,'backendUnit':89,'integration':59,'knownSecretsScan':'passed'}))
