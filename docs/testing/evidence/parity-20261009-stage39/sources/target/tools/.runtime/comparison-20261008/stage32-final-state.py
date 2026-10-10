"""Read-only final audit against preserved pre-V33, pre-V34 and pre-format4 checkpoints."""
from pathlib import Path
import hashlib
import json
import subprocess
import urllib.request
import pymysql

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
private = root / 'deploy/.secrets/comparison-20261008'
old = json.loads((run / 'stage28-before-v33-private.json').read_text(encoding='utf-8'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
normalized = lambda rows: sorted(json.dumps(r, ensure_ascii=False, sort_keys=True, default=str) for r in rows)
result = {}
for kind in ('java', 'python'):
    java = kind == 'java'
    result[kind] = {}
    with pymysql.connect(host='127.0.0.1', port=33308, user=kind+'_compare',
            password=(private/('mysql-'+kind)).read_text().strip(), database='sn_'+kind+'_compare',
            cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
        tables = ('sn_item','sn_key','sn_pi_counter','sn_rule','sn_rule_version','sn_acquire_batch','sn_print','sn_transfer')
        tables += ('sn_acquire_member','sn_print_member','sn_pi_rule_choice','sn_generation_job','sn_bill_generation','sn_source_entry_quota','sn_transfer_member') if java else ('sn_bill_gen','sn_allocation','sn_transfer_item')
        for table in tables:
            c.execute('SELECT * FROM '+table)
            current = c.fetchall()
            assert normalized(current) == normalized(old[kind][table]), (kind, table, 'original business changed')
            result[kind][table] = {'rows':len(current),'unchanged':True}
        table = 'sys_user' if java else 'sn_user'
        fields = ['id','username','password_hash','security_version','must_change_password'] if java else ['id','emp_no','password_hash','must_change_pwd','role','factory_code','status']
        c.execute('SELECT '+','.join(fields)+' FROM '+table)
        assert normalized(c.fetchall()) == normalized([{k:r[k] for k in fields} for r in old[kind][table]])
        result[kind]['accountSecurityUnchanged'] = True
        c.execute('SELECT enabled,interval_minutes FROM sn_order_sync_schedule WHERE id=1')
        assert c.fetchone() == {'enabled':0,'interval_minutes':1440}
        c.execute('SELECT bill_no FROM sn_order_bill WHERE active=1 ORDER BY bill_no' if java else 'SELECT DISTINCT bill_no FROM sn_prd_mo ORDER BY bill_no')
        assert [r['bill_no'] for r in c.fetchall()] == ['CMP-MO-001','CMP-MO-002','CMP-MO-FRACTION']
        if java:
            c.execute('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')
            assert c.fetchone()['version'] == '34'
            result[kind]['schema'] = '34'
            c.execute("SELECT COUNT(*) n FROM sys_task WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')")
            assert c.fetchone()['n'] == 0
            c.execute('SELECT COUNT(*) n FROM sn_import_chunk')
            assert c.fetchone()['n'] == 0
            for before in old[kind]['sn_query_export']:
                c.execute('SELECT * FROM sn_query_export WHERE task_id=%s',(before['task_id'],))
                after = c.fetchone()
                assert after['format_version'] == 1
                assert normalized([{k:after[k] for k in before}]) == normalized([before])
                c.execute('SELECT * FROM sn_query_export_row WHERE task_id=%s',(before['task_id'],))
                assert normalized(c.fetchall()) == normalized([r for r in old[kind]['sn_query_export_row'] if r['task_id']==before['task_id']])
            result[kind]['legacyQuerySnapshotsPreservedAsFormat1'] = len(old[kind]['sn_query_export'])
            c.execute('SELECT format_version,COUNT(*) count FROM sn_query_export GROUP BY format_version')
            result[kind]['exportFormats'] = c.fetchall()

for name, digest in json.loads((run/'stage28-frozen-sql.json').read_text()).items():
    assert sha(root/'backend/adtek-server/src/main/resources/db/migration'/name) == digest
result['V1ThroughV32Unchanged'] = True
summary=json.loads((root/'docs/testing/evidence/parity-20261009-stage28/summary.json').read_text(encoding='utf8'))
for name,digest in summary['sourceHashes'].items():
    if name.endswith('.sql') or name.startswith('../sn-center-python/'):
        assert sha(root/name)==digest,name
result['V33AndPrototypeSourcesUnchanged']=True
prior31=json.loads((root/'docs/testing/evidence/parity-20261009-stage31/summary.json').read_text(encoding='utf8'))
for name,digest in prior31['sourceHashes'].items():
    if name.startswith('../sn-center-python/'):
        assert sha(root/name)==digest,name
result['stage31PrototypeSourcesUnchanged']=True
files = json.loads((run/'stage28-original-file-hashes.json').read_text())
for name, digest in files.items():
    assert sha(root/'deploy/data/comparison-20261008/files/objects'/name) == digest
result['originalFileBytesUnchanged'] = len(files)
assert json.loads((run/'extra-orders.json').read_text()) == []
assert json.loads((run/'k3-control.json').read_text()) == {}
assert not list(run.glob('acquire*fixture-*.json'))
assert not list(run.glob('target-fixture-*.json'))
assert not (run/'query-recovery-fixture.json').exists()
prior=json.loads((root/'docs/testing/evidence/parity-20261009-stage31/summary.json').read_text(encoding='utf8'))
jar='backend/adtek-server/target/adtek-server-acceptance.jar'
assert sha(run/'stage32-before.jar')==prior['sourceHashes'][jar]
result['previousAcceptanceJarBackedUpByteIdentically']=True
result['currentAcceptanceJarSha256']=sha(root/jar)
assert result['currentAcceptanceJarSha256'] != prior['sourceHashes'][jar]
assert not list(run.glob('audit-view-fixture-*.json'))
for kind in ('java','python'):
    proof=json.loads((run/f'audit-view-clean-{kind}.json').read_text(encoding='utf8'))
    assert proof['syntheticRowsRemoved']==54 and proof['businessAndAccountSecurityUnchanged']
result['ownedAuditFixturesCleaned']=True
before34=json.loads((run/'stage32-before-v34-private.json').read_text(encoding='utf8'))
with pymysql.connect(host='127.0.0.1',port=33308,user='java_compare',
        password=(private/'mysql-java').read_text().strip(),database='sn_java_compare',
        cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
    for table,keys in [('sn_audit',('id','created_at')),('sn_audit_archive',('id','created_at')),
            ('sn_audit_export',('task_id',)),('sn_audit_export_row',('task_id','ordinal'))]:
        c.execute('SELECT * FROM '+table)
        current={tuple(str(r[k]) for k in keys):r for r in c.fetchall()}
        for row in before34['java'][table]:
            now=current[tuple(str(row[k]) for k in keys)]
            assert normalized([{k:now[k] for k in row}])==normalized([row]),table
            if table=='sn_audit_export': assert now['format_version']==1
        result['preservedBeforeV34_'+table]=len(before34['java'][table])
    c.execute("SELECT COUNT(*) n FROM sn_audit_identity x WHERE NOT EXISTS(SELECT 1 FROM sn_audit a WHERE a.id=x.audit_id AND a.created_at=x.created_at) AND NOT EXISTS(SELECT 1 FROM sn_audit_archive a WHERE a.id=x.audit_id AND a.created_at=x.created_at)")
    assert c.fetchone()['n']==0
    c.execute('SELECT format_version,COUNT(*) n FROM sn_audit_export GROUP BY format_version')
    result['auditExportVersions']=c.fetchall()
for name,digest in json.loads((run/'stage32-frozen-sql.json').read_text()).items():
    assert sha(root/'backend/adtek-server/src/main/resources/db/migration'/name)==digest,name
result['V1ThroughV33Unchanged']=True
files32=json.loads((run/'stage32-original-file-hashes.json').read_text())
for name,digest in files32.items():
    assert sha(root/'deploy/data/comparison-20261008/files/objects'/name)==digest,name
result['preV34FilesUnchanged']=len(files32)
before4=json.loads((run/'stage32-before-format4-private.json').read_text(encoding='utf8'))
with pymysql.connect(host='127.0.0.1',port=33308,user='java_compare',
        password=(private/'mysql-java').read_text().strip(),database='sn_java_compare',
        cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
    for table,keys in [('sn_audit_export',('task_id',)),('sn_audit_export_row',('task_id','ordinal')),
            ('sys_file',('id',))]:
        c.execute('SELECT * FROM '+table)
        current={tuple(str(r[k]) for k in keys):r for r in c.fetchall()}
        for row in before4[table]:
            assert normalized([current[tuple(str(row[k]) for k in keys)]])==normalized([row]),table
        result['preservedBeforeFormat4_'+table]=len(before4[table])
    for file in before4['sys_file']:
        if file['sha256'] and file['state']=='READY':
            path=root/'deploy/data/comparison-20261008/files/objects'/file['storage_key']
            assert sha(path)==file['sha256'],file['id']
result['allPreFormat4ReadyFileBytesUnchanged']=True
result['noActiveTasksOrOwnedFixtures'] = True
for stage in (26,27,28,29,30,31):
    directory = root/f'docs/testing/evidence/parity-20261009-stage{stage}'
    manifest = json.loads((directory/'manifest.json').read_text(encoding='utf8'))
    pairs = manifest.items() if isinstance(manifest,dict) else [(v['path'],v['sha256']) for v in manifest]
    for name, digest in pairs:
        assert sha(directory/name)==digest, (stage,name)
    result[f'stage{stage}FrozenFilesUnchanged'] = len(manifest)
directory=root/'docs/testing/evidence/parity-20261009-audit-observed'
manifest=json.loads((directory/'manifest.json').read_text(encoding='utf8'))
for name,digest in manifest.items(): assert sha(directory/name)==digest,name
result['auditObservationFrozenFilesUnchanged']=len(manifest)
result['health'] = json.load(urllib.request.urlopen('http://127.0.0.1:18080/actuator/health/readiness'))
assert result['health']['status'] == 'UP'
result['pythonHealth'] = json.load(urllib.request.urlopen('http://127.0.0.1:8000/health/ready'))
assert result['pythonHealth']['status'] == 'ok'
result['web'] = {str(port):urllib.request.urlopen(f'http://127.0.0.1:{port}').status for port in (8000,15173)}
result['k3'] = json.load(urllib.request.urlopen('http://127.0.0.1:19090'))
assert result['k3']['orders']==3 and result['k3']['entries']==4
def canonical(v):
    if isinstance(v,dict): return {k:sorted(x) if k=='type' and isinstance(x,list) and all(isinstance(t,str) for t in x) else canonical(x) for k,x in v.items()}
    if isinstance(v,list): return [canonical(x) for x in v]
    return v
assert canonical(json.load(urllib.request.urlopen('http://127.0.0.1:18080/v3/api-docs'))) == canonical(json.loads((root/'frontend/openapi/adtek-sn.json').read_text(encoding='utf8')))
result['liveOpenApiMatches'] = True
result['prototypeStatus'] = subprocess.check_output(['git','status','--short'],cwd=root.parent/'sn-center-python',text=True).strip()
assert result['prototypeStatus'] == '?? sn.env'
(run/'stage32-final-state.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print('V34 ready; baseline business/accounts/old files/frozen SQL/evidence preserved; no active fixture/tasks.')
