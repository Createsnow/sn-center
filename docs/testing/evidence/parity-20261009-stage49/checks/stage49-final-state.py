"""Read-only preservation check for the main comparison databases and frozen evidence."""
from pathlib import Path
import hashlib,json,urllib.request
import pymysql
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';private=root/'deploy/.secrets/comparison-20261008'
old=json.loads((run/'stage49-before-private.json').read_text('utf8'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
norm=lambda rows:sorted(json.dumps(r,ensure_ascii=False,sort_keys=True,default=str) for r in rows)
result={}
for kind in ('java','python'):
    result[kind]={}
    with pymysql.connect(host='127.0.0.1',port=33308,user=kind+'_compare',password=(private/('mysql-'+kind)).read_text().strip(),database='sn_'+kind+'_compare',cursorclass=pymysql.cursors.DictCursor) as con,con.cursor() as c:
        for table,before in old[kind].items():
            c.execute('SELECT * FROM '+table); after=c.fetchall()
            if kind=='java' and table=='sys_oper_log':
                oldids={r['id'] for r in before}
                assert norm([r for r in after if r['id'] in oldids])==norm(before),(kind,table)
                new=[r for r in after if r['id'] not in oldids]
                assert all(r['action']=='auth:REFRESH' and r['success']==1 for r in new)
                result[kind][table]={'oldRows':len(before),'oldRowsUnchanged':True,'additionalRefreshLogs':len(new)}
            else:
                assert norm(after)==norm(before),(kind,table)
                result[kind][table]={'rows':len(after),'allRowsUnchanged':True}
for category,base in [('file',root),('frozen-sql',root),('prototype',root.parent/'sn-center-python')]:
    hashes=json.loads((run/f'stage49-{category}-hashes.json').read_text('utf8'))
    for name,digest in hashes.items(): assert sha(base/name)==digest,(category,name)
    result[category]={'files':len(hashes),'allBytesUnchanged':True}
for m in sorted((root/'docs/testing/evidence').glob('*/manifest.json')):
    if m.parent.name.startswith('parity-20261009-stage49'):continue
    data=json.loads(m.read_text('utf8')); pairs=data.items() if isinstance(data,dict) else [(v['path'],v['sha256']) for v in data]
    for name,digest in pairs: assert sha(m.parent/name)==digest,(m.parent.name,name)
    result[m.parent.name]={'frozenFiles':len(data),'unchanged':True}
for port,path in [(18080,'/actuator/health/readiness'),(8000,'/health/ready')]:
    result[str(port)]=json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}'+path))
    assert result[str(port)]['status'] in ('UP','ok')
result['web']={str(port):urllib.request.urlopen(f'http://127.0.0.1:{port}').status for port in (15173,8000)}
result['jarSha256']=sha(root/'backend/adtek-server/target/adtek-server-acceptance.jar')
(run/'stage49-final-state.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('Main database rows, accounts, files, prototype and frozen evidence preserved; main services ready.')
