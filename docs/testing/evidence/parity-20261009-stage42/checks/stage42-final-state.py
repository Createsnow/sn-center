"""Read-only preservation check after password/authentication UI operations; existing V38 unchanged."""
from pathlib import Path
import hashlib,json,subprocess,urllib.request
import pymysql
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';private=root/'deploy/.secrets/comparison-20261008'
old=json.loads((run/'stage42-before-private.json').read_text('utf8'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
norm=lambda rows:sorted(json.dumps(r,ensure_ascii=False,sort_keys=True,default=str) for r in rows)
result={}
for kind in ('java','python'):
    java=kind=='java';result[kind]={}
    with pymysql.connect(host='127.0.0.1',port=33308,user=kind+'_compare',password=(private/('mysql-'+kind)).read_text().strip(),database='sn_'+kind+'_compare',cursorclass=pymysql.cursors.DictCursor) as con,con.cursor() as c:
        tables=['sn_item','sn_key','sn_pi_counter','sn_rule','sn_rule_version','sn_acquire_batch','sn_print','sn_transfer']
        tables+=['sn_acquire_member','sn_print_member','sn_pi_rule_choice','sn_generation_job','sn_bill_generation','sn_source_entry_quota','sn_transfer_member'] if java else ['sn_bill_gen','sn_allocation','sn_transfer_item']
        for table in tables:
            c.execute('SELECT * FROM '+table);rows=c.fetchall();assert norm(rows)==norm(old[kind][table]),(kind,table)
            result[kind][table]={'rows':len(rows),'unchanged':True}
        mutable = {'sys_user','sys_oper_log','sn_audit','sn_audit_archive','sn_audit_identity','sn_business_number'} if java else {'sn_user','sn_audit','sn_audit_archive'}
        for table in old[kind]:
            if table in mutable: continue
            c.execute('SELECT * FROM '+table)
            assert norm(c.fetchall()) == norm(old[kind][table]), (kind, table, 'full-table preservation')
        if java:
            for relation in ('sys_user_role','sn_user_factory'):
                c.execute('SELECT * FROM '+relation);assert norm(c.fetchall())==norm(old[kind][relation]),relation
        table='sys_user' if java else 'sn_user'
        fields=['id','principal_type','username','password_hash','display_name','dept_id','enabled','security_version','must_change_password','language','create_by','create_time'] if java else ['id','emp_no','password_hash','name','must_change_pwd','pwd_changed_at','role','factory_code','status','lang']
        c.execute('SELECT '+','.join(fields)+' FROM '+table)
        assert norm(c.fetchall())==norm([{k:r[k] for k in fields} for r in old[kind][table]])
        result[kind]['accountSecurityUnchanged']=True
        if java:
            c.execute('SELECT id,credential_version FROM sys_user')
            assert {str(r['id']):r['credential_version'] for r in c.fetchall()} == {str(r['id']):r['credential_version'] for r in old[kind][table]}
            result[kind]['existingCredentialVersionsUnchanged']=True
        c.execute('SELECT enabled,interval_minutes FROM sn_order_sync_schedule WHERE id=1');assert c.fetchone()=={'enabled':0,'interval_minutes':1440}
        for table,keys in [('sn_audit',('id','created_at'))]+([('sys_oper_log',('id',)),('sn_audit_archive',('id','created_at')),('sn_audit_identity',('creation_order',)),('sn_audit_export',('task_id',)),('sn_audit_export_row',('task_id','ordinal')),('sn_query_export',('task_id',)),('sn_query_export_row',('task_id','ordinal')),('sys_file',('id',))] if java else []):
            c.execute('SELECT * FROM '+table);current={tuple(str(r[k]) for k in keys):r for r in c.fetchall()}
            for row in old[kind][table]:
                now=current[tuple(str(row[k]) for k in keys)]
                assert norm([{k:now[k] for k in row}])==norm([row]),(kind,table)
            result[kind][table+'OldRowsPreserved']=len(old[kind][table])
        if java:
            c.execute('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1');assert c.fetchone()['version']=='38'
            result[kind]['schema']='38'
            c.execute("SELECT COUNT(*) n FROM sys_task WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')");assert c.fetchone()['n']==0
            c.execute("SELECT COUNT(*) n FROM sn_audit_identity i WHERE NOT EXISTS(SELECT 1 FROM sn_audit a WHERE a.id=i.audit_id AND a.created_at=i.created_at) AND NOT EXISTS(SELECT 1 FROM sn_audit_archive a WHERE a.id=i.audit_id AND a.created_at=i.created_at)");assert c.fetchone()['n']==0
            c.execute('SELECT format_version,COUNT(*) n FROM sn_audit_export GROUP BY format_version');result['auditFormats']=c.fetchall()
for name,digest in json.loads((run/'stage42-frozen-sql-hashes.json').read_text('utf8')).items():assert sha(root/'backend/adtek-server/src/main/resources/db/migration'/name)==digest,name
result['V1ThroughV38Unchanged']=True
result['V38DeployedBytesUnchanged']=True
assert not list(private.glob('auth-fixture-*.json')) or all(p.name.endswith('.completed.json') for p in private.glob('auth-fixture-*.json'))
files=json.loads((run/'stage42-file-hashes.json').read_text('utf8'))
for name,digest in files.items():assert sha(root/'deploy/data/comparison-20261008/files/objects'/name)==digest,name
result['oldFileBytesUnchanged']=len(files)
assert not list(run.glob('rule-fixture-*.json')) and not list(run.glob('audit-view-fixture-*.json'))
assert json.loads((run/'extra-orders.json').read_text('utf8'))==[] and json.loads((run/'k3-control.json').read_text('utf8'))=={}
assert all(p.name.endswith('.completed.json') for p in private.glob('account-editor-*.json'))
assert all(p.name.endswith('.completed.json') for p in private.glob('account-session-*.json'))
assert all(p.name.endswith('.completed.json') for p in private.glob('account-actions-*.json'))
result['noActiveTasksOrOwnedFixtures']=True
for name,digest in json.loads((run/'stage42-prototype-hashes.json').read_text('utf8')).items():
    assert sha(root.parent/'sn-center-python'/name)==digest,name
result['prototypeSourcesUnchanged']=True
for manifest_file in sorted((root/'docs/testing/evidence').glob('*/manifest.json')):
    directory=manifest_file.parent;name=directory.name
    if name=='parity-20261009-stage42':continue
    manifest=json.loads((directory/'manifest.json').read_text('utf8'))
    pairs=manifest.items() if isinstance(manifest,dict) else [(v['path'],v['sha256']) for v in manifest]
    for file,digest in pairs:assert sha(directory/file)==digest,(name,file)
    result[name+'FrozenUnchanged']=len(manifest)
result['javaReady']=json.load(urllib.request.urlopen('http://127.0.0.1:18080/actuator/health/readiness'))
assert result['javaReady']['status']=='UP'
result['pythonReady']=json.load(urllib.request.urlopen('http://127.0.0.1:8000/health/ready'));assert result['pythonReady']['status']=='ok'
result['web']={str(p):urllib.request.urlopen(f'http://127.0.0.1:{p}').status for p in (8000,15173)}
result['k3']=json.load(urllib.request.urlopen('http://127.0.0.1:19090'));assert result['k3']['orders']==3 and result['k3']['entries']==4
def canonical(v):
    if isinstance(v,dict):return {k:sorted(x) if k=='type' and isinstance(x,list) and all(isinstance(t,str) for t in x) else canonical(x) for k,x in v.items()}
    if isinstance(v,list):return [canonical(x) for x in v]
    return v
assert canonical(json.load(urllib.request.urlopen('http://127.0.0.1:18080/v3/api-docs')))==canonical(json.loads((root/'frontend/openapi/adtek-sn.json').read_text('utf8')))
result['liveOpenApiMatches']=True
result['prototypeStatus']=subprocess.check_output(['git','status','--short'],cwd=root.parent/'sn-center-python',text=True).strip();assert result['prototypeStatus']=='?? sn.env'
(run/'stage42-final-state.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
result['backendChangedFiles']=[name for name,digest in json.loads((run/'stage42-backend-hashes.json').read_text('utf8')).items() if sha(root/name)!=digest]
result['deployedJarSha256']=sha(root/'backend/adtek-server/target/adtek-server-acceptance.jar')
assert not list(private.glob('users-directory-*.json')) or all(p.name.endswith('.completed.json') for p in private.glob('users-directory-*.json'))
(run/'stage42-final-state.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('V38 ready; original business/security/roles/audit/files and V1-V38/prototype/frozen evidence preserved.')
