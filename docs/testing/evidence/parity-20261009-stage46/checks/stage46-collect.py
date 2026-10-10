"""Freeze this stage's observed checks and exact sources, excluding private state and credentials."""
from pathlib import Path
import hashlib,json,re,shutil
import xml.etree.ElementTree as ET

root=Path.cwd(); run=root/'tools/.runtime/comparison-20261008'
dest=root/'docs/testing/evidence/parity-20261009-stage46'
assert not (dest/'manifest.json').exists(), 'Do not replace frozen evidence'
dest.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def copy(source,relative):
    target=dest/relative;target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(source,target)
report=json.loads((root/'frontend/test-results/parity/results.json').read_text('utf8'))
assert report['stats']['expected']==6 and report['stats']['unexpected']==report['stats']['flaky']==report['stats']['skipped']==0
copy(root/'frontend/test-results/parity/results.json',Path('ui/results.json'))
main=root/'frontend/test-results/stage46-final10'
for p in main.rglob('*'):
    if p.is_file():copy(p,Path('ui')/p.relative_to(main))
for i in range(1,10):
    folder=root/f'frontend/test-results/stage46-ui{i}'
    for p in folder.rglob('*'):
        if p.is_file():copy(p,Path(f'diagnostics/ui{i}')/p.relative_to(folder))
for p in run.glob('stage46-*.log'):copy(p,Path('logs')/p.name)
for suffix in ('comparison','factory-comparison','audit-comparison','final-state'):
    p=run/f'stage46-{suffix}.json';copy(p,Path('checks')/p.name)
for name in ('compare','factory-compare','audit-compare','final-state','collect'):
    p=run/f'stage46-{name}.py';copy(p,Path('checks')/p.name)
log=(run/'stage46-backend3.log').read_text('utf8',errors='replace')
assert 'BUILD SUCCESS' in log
tests=[]
for m in re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: ([\d.]+) s -- in ([\w.]+)',log):
    n,failed,errors,skipped,seconds,name=m
    assert int(failed)==int(errors)==int(skipped)==0
    tests.append({'name':name,'tests':int(n),'seconds':float(seconds)})
    for p in (root/'backend').glob(f'*/target/*-reports/TEST-{name}.xml'):
        suite=ET.parse(p).getroot()
        assert int(suite.get('tests'))==int(n)
        copy(p,Path('backend')/p.name)
for i in (1,2):
    for p in (run/f'stage46-backend{i}-reports').glob('*'):
        if p.is_file():copy(p,Path(f'diagnostics/backend{i}')/p.name)
sources=set()
for kind in ('backend','frontend'):
    before=json.loads((run/f'stage46-{kind}-hashes.json').read_text('utf8'))
    for name,digest in before.items():
        p=root/name
        if sha(p)!=digest:sources.add(name)
sources.update([
    'backend/adtek-server/src/main/resources/db/migration/V40__factory_mapping_origin_and_order_names.sql',
    'backend/adtek-server/src/test/java/com/adtek/server/FactoryMappingMigrationIT.java',
    'frontend/e2e/parity/factory-identity.spec.ts','frontend/e2e/parity/factories.spec.ts',
    'frontend/e2e/parity/factory-audit.spec.ts','frontend/playwright.parity.config.ts',
    'tools/testing/parity-factory-identity-fixture.py','tools/testing/parity-factory-fixture.py',
    'tools/testing/parity-k3-fixture.py',
])
for name in sorted(sources):copy(root/name,Path('sources')/name)
copy(root/'frontend/test-results/unit.json',Path('checks/frontend-unit.json'))
summary={'goal':'active','date':'2026-10-09','schema':'40',
         'backendUnitTests':sum(t['tests'] for t in tests if not t['name'].endswith('IT')),
         'backendIntegration':[t for t in tests if t['name'].endswith('IT')],
         'ui':report['stats'],'screenshots':len(list((dest/'ui').rglob('*.png'))),
         'diagnosticScreenshots':len(list((dest/'diagnostics').rglob('*.png'))),
         'sources':{name:sha(root/name) for name in sorted(sources)},
         'runtime':json.loads((run/'stage46-final-state.json').read_text('utf8')),
         'scope':'Factory/name-based current-order ownership and warning display. All P01–P16 and transaction/fault/role matrices remain incomplete.'}
(dest/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf8')
# 原账号密码不得进入公开证据。仅检查本机读取，不输出秘密值。
private=root/'deploy/.secrets/comparison-20261008'
secret_files=list(private.glob('*-admin-password')) + [private/n for n in ('mysql-root','mysql-java','mysql-python','mysql-pytest','redis','jwt','java-admin-initial-password','python-admin-initial-password')]
secrets=[p.read_bytes().strip() for p in secret_files]
for p in dest.rglob('*'):
    if p.is_file():
        data=p.read_bytes()
        assert not any(secret and secret in data for secret in secrets), ('private credential found',p.name)
manifest={p.relative_to(dest).as_posix():sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({k:v for k,v in summary.items() if k not in ('sources','runtime')},ensure_ascii=False))
print('Frozen files:',len(manifest),'sources:',len(sources))
