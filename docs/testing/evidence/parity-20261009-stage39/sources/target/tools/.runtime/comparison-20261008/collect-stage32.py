"""Freeze verified stage32 results once, keeping all failed/intermediate evidence separate."""
from pathlib import Path
import ast
import hashlib
import json
import re
import shutil

root=Path.cwd(); run=root/'tools/.runtime/comparison-20261008'
dest=root/'docs/testing/evidence/parity-20261009-stage32'
assert not dest.exists(), 'Do not overwrite frozen or partial evidence'
ui=json.loads((run/'stage32-audit-verified-ui-results.json').read_text('utf8'))
assert ui['stats']['expected']==4 and all(ui['stats'][k]==0 for k in ('unexpected','skipped','flaky'))
comparison=json.loads((run/'stage32-comparison.json').read_text('utf8'))
assert len(comparison['tables'])==15 and all(v['allRawCellsAndOrderEqual'] for v in comparison['tables'])
assert len(comparison['layouts'])==15 and all(v['equal'] for v in comparison['layouts'])
assert sum(v['cells'] for v in comparison['details'])==20
assert all(v['textGeometryAndStylesEqual'] for v in comparison['details'])
assert len(comparison['files'])==4 and all(v['allCellValuesTypesOrderSheetAndFilenameEqual'] for v in comparison['files'])
state=json.loads((run/'stage32-final-state.json').read_text('utf8'))
assert state['noActiveTasksOrOwnedFixtures'] and state['ownedAuditFixturesCleaned'] and state['java']['schema']=='34'
assert state['allPreFormat4ReadyFileBytesUnchanged']
unit=json.loads((root/'frontend/test-results/unit.json').read_text('utf8'))
assert unit['success'] and unit['numTotalTests']==78 and unit['numFailedTests']==0
log=(run/'stage32-audit-verify5.log').read_text('utf8')
assert 'BUILD SUCCESS' in log
suites=[{'tests':int(n),'failures':int(f),'errors':int(e),'skipped':int(s),'name':name} for n,f,e,s,name in re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: .*? -- in ([\w.]+)',log)]
assert sum(v['tests'] for v in suites if not v['name'].endswith('IT'))==86
assert sum(v['tests'] for v in suites if v['name'].endswith('IT'))==13
assert all(v['failures']==v['errors']==v['skipped']==0 for v in suites)
assert 'Total: 179 tests in 22 files' in (run/'stage32-registered-ui.log').read_text('utf8')
private=root/'deploy/.secrets/comparison-20261008'; secrets=[]
for file in private.iterdir():
    if file.is_file() and file.name not in ('init.sql','role-accounts.json'):
        value=file.read_text('utf8').strip()
        if len(value)>8: secrets.append(value)
for system in json.loads((private/'role-accounts.json').read_text()).values():
    for account in system.values():
        secrets.extend(v for v in account.values() if isinstance(v,str) and len(v)>8)
old=ast.parse((run/'collect-stage28.py').read_text('utf8'))
functions=[n for n in old.body if isinstance(n,ast.FunctionDef) and n.name in ('scrub','copy','tree')]
assert len(functions)==3
exec(compile(ast.Module(body=functions,type_ignores=[]),'reviewed-copy-helpers','exec'))
tree(root/'frontend/test-results/stage32-audit-verified-ui',dest/'ui')
for name in ('initial','final'):
    tree(root/f'frontend/test-results/stage32-audit-{name}-ui',dest/'diagnostics'/name)
    copy(run/f'stage32-audit-{name}-ui-results.json',dest/'diagnostics'/f'{name}-results.json')
copy(run/'stage32-format3-comparison-failure.json',dest/'diagnostics/format3-comparison-failure.json')
for file in run.glob('stage32-*.log'):
    copy(file,dest/'diagnostics'/file.name)
for name in ('stage32-audit-verified-ui-results.json','stage32-audit-verified-ui.log','stage32-comparison.json',
        'stage32-final-state.json','stage32-audit-verify5.log','stage32-web-final-checks.log',
        'stage32-backend-format4-check.log','stage32-frontend-format4-check.log',
        'stage32-frontend-typecheck4.log','stage32-verified-lint.log','stage32-registered-ui.log',
        'stage32-python-check.log'):
    copy(run/name,dest/name)
copy(root/'frontend/test-results/unit.json',dest/'frontend-unit.json')
for name in ('stage32-compare.py','stage32-final-state.py','collect-stage32.py'):
    copy(run/name,dest/'verification'/name)
(dest/'backend-suites.json').write_text(json.dumps(suites,indent=2),'utf8')
sources=set(json.loads((root/'docs/testing/evidence/parity-20261009-stage31/summary.json').read_text('utf8'))['sourceHashes'])
sources.update([
'AGENTS.md',
'backend/adtek-server/src/main/resources/db/migration/V34__audit_export_format_and_creation_order.sql',
'backend/adtek-server/src/main/resources/application.yml',
'backend/adtek-sn/src/main/java/com/adtek/sn/domain/SnAuditExportEntity.java',
'backend/adtek-sn/src/main/java/com/adtek/sn/domain/SnAuditEntity.java',
'backend/adtek-sn/src/main/java/com/adtek/sn/convert/SnAuditConvert.java',
'backend/adtek-sn/src/main/java/com/adtek/sn/convert/SnQueryConvert.java',
'backend/adtek-sn/src/main/java/com/adtek/sn/service/impl/SnAuditObserver.java',
'backend/adtek-sn/src/main/java/com/adtek/sn/service/support/SnWorkSettings.java',
'backend/adtek-sn/src/main/java/com/adtek/sn/service/support/PrintFiles.java',
'backend/adtek-sn/src/test/java/com/adtek/sn/service/support/SnAuditFilesTest.java',
'backend/adtek-sn/src/test/resources/audit-file-goldens/v1.json',
'backend/adtek-sn/src/test/resources/audit-file-goldens/v2.json',
'backend/adtek-sn/src/test/resources/audit-file-goldens/v3.json',
'frontend/e2e/parity/audit-files.spec.ts',
'frontend/src/api/snAudit.test.ts',
'frontend/src/api/client.ts',
'../sn-center-python/app/services/audit_query.py',
'../sn-center-python/app/services/files.py',
'tools/.runtime/comparison-20261008/stage32-compare.py',
'tools/.runtime/comparison-20261008/stage32-final-state.py',
'tools/.runtime/comparison-20261008/collect-stage32.py',
])
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
hashes={name:sha(root/name) for name in sorted(sources)}
summary={
'stage':32,'goalComplete':False,'allCasesRerun':False,'ui':ui['stats'],
'workers':1,'retries':0,'registeredCases':179,'distinctCasesRun':4,
'uiScreenshots':len(list((dest/'ui').rglob('*.png'))),
'orderedRawTablePairs':15,'exactMainLayoutPairs':15,'exactExpandedDetailCells':20,
'expandedDetailTextEqual':True,'fullCellValueTypeOrderFilePairs':4,
'crossImplementationZipBytesEqual':False,'legacyActualWorkbookSha256Versions':[1,2,3],
'backendUnitTests':86,'backendIntegrationTests':13,'frontendUnitTests':78,
'schema':'34','nextMigration':'35','currentNewAuditExportFormat':4,'sourceHashes':hashes,
'syntheticAuditSamplesPerSide':54,'realAuditProductionVerified':False,
'remaining':[
'P12 business audit producer projection, real batch/transfer UI, action families and platform audit facts',
'P12 actual failure messages and detail field semantics, unknown/multiple roles, old audit creation order',
'P12 other roles and permissions, failure/recovery UI, 200000-row capacity/performance, full styling and narrow widths',
'P11 remaining states/filters/copy/errors/capacity/performance and full old-format replay UI',
'Source page size race, pre-V31 SN creation order, all P01-P16 remaining acceptance and 500000-row import performance'
],
'warnings':['largeBundle','xlsxDefaultStyleWarning','WindowsSourceCarriageReturnsReadAsLineFeeds'],
'diagnostics':[
'Initial mixed-revision run: filename guard rejected audit_export.xlsx, then empty text type mismatch',
'Format3 four UI flows passed but complete comparison found CRLF versus two LF in two workbooks',
'First format4 golden capture truncated original metadata microseconds; fixed capture from actual workbook without changing expected SHA256'
]}
assert summary['uiScreenshots']==36
(dest/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf8')
for file in dest.rglob('*'):
    if file.is_file() and file.suffix in ('.json','.log','.csv','.py'):
        text=file.read_text('utf-8-sig')
        assert not any(secret in text for secret in secrets),file.name
manifest={p.relative_to(dest).as_posix():sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'files':len(manifest),'sources':len(hashes),'screenshots':36,'goalComplete':False}))
