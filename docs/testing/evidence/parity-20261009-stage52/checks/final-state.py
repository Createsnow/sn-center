"""Read-only preservation check for the main comparison databases and frozen evidence."""
from pathlib import Path
import hashlib,json,urllib.request
import pymysql
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';private=root/'deploy/.secrets/comparison-20261008'
old=json.loads((run/'stage52-before-private.json').read_text('utf8'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
norm=lambda rows:sorted(json.dumps(r,ensure_ascii=False,sort_keys=True,default=str) for r in rows)
result={'scope': 'Read-only inventory against the stage52 baseline. Preserve any concurrent main user activity; never restore an earlier snapshot.'}
for kind in ('java','python'):
    result[kind]={}
    with pymysql.connect(host='127.0.0.1',port=33308,user=kind+'_compare',password=(private/('mysql-'+kind)).read_text().strip(),database='sn_'+kind+'_compare',cursorclass=pymysql.cursors.DictCursor) as con,con.cursor() as c:
        for table,before in old[kind].items():
            c.execute('SELECT * FROM '+table); after=c.fetchall()
            c.execute('SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s AND CONSTRAINT_NAME="PRIMARY" ORDER BY ORDINAL_POSITION',(table,))
            pk=[v['COLUMN_NAME'] for v in c.fetchall()]
            if not pk:
                assert set(norm(before)).issubset(set(norm(after))), (kind,table,'missing original rows')
                result[kind][table]={'beforeRows':len(before),'afterRows':len(after),'originalRowsUnchanged':True}
                continue
            key=lambda row:tuple(json.dumps(row[v],default=str,sort_keys=True) for v in pk)
            prior={key(v):v for v in before}; current={key(v):v for v in after}
            assert prior.keys()<=current.keys(),(kind,table,'missing original keys')
            changed={}
            for k,v in prior.items():
                for field in v:
                    if norm([v[field]])!=norm([current[k][field]]):changed[field]=changed.get(field,0)+1
            result[kind][table]={'beforeRows':len(before),'afterRows':len(after),'originalKeysPresent':True,'changedOriginalFields':changed,'allRowsUnchanged':norm(before)==norm(after)}
            if table in ('sys_user','sn_user','users'):
                protected=[v for v in before[0] if any(t in v for t in ('password','username','login_name'))] if before else []
                for k,v in prior.items():
                    assert all(norm([v[f]])==norm([current[k][f]]) for f in protected),(kind,table,'original credentials changed')
                result[kind][table]['originalCredentialFieldsUnchanged']=protected
            if table in ('sys_user_role','sn_user_factory'):
                assert not changed,(kind,table,'original memberships changed')
            if table in ('sys_oper_log','sn_audit'):
                assert not changed,(kind,table,'historical audit changed')
                actions={}
                for k,v in current.items():
                    if k not in prior:actions[v['action']]=actions.get(v['action'],0)+1
                result[kind][table]['newActions']=actions
for category,base in [('file',root),('frozen-sql',root),('prototype',root.parent/'sn-center-python')]:
    hashes=json.loads((run/f'stage52-{category}-hashes.json').read_text('utf8'))
    for name,digest in hashes.items(): assert sha(base/name)==digest,(category,name)
    result[category]={'files':len(hashes),'allBytesUnchanged':True}
for m in sorted((root/'docs/testing/evidence').glob('*/manifest.json')):
    if m.parent.name.startswith('parity-20261009-stage52'):continue
    data=json.loads(m.read_text('utf8')); pairs=data.items() if isinstance(data,dict) else [(v['path'],v['sha256']) for v in data]
    for name,digest in pairs: assert sha(m.parent/name)==digest,(m.parent.name,name)
    result[m.parent.name]={'frozenFiles':len(data),'unchanged':True}
for port,path in [(18080,'/actuator/health/readiness'),(8000,'/health/ready')]:
    result[str(port)]=json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}'+path))
    assert result[str(port)]['status'] in ('UP','ok')
result['web']={str(port):urllib.request.urlopen(f'http://127.0.0.1:{port}').status for port in (15173,8000)}
result['jarSha256']=sha(root/'backend/adtek-server/target/adtek-server-acceptance.jar')
(run/'stage52-final-state.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('Original keys, credential fields, memberships, historical audit, files, prototype and frozen evidence preserved. Concurrent main database changes inventoried; services ready.')
