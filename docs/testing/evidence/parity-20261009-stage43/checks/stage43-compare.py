from pathlib import Path
import json
root=Path.cwd(); base=root/'frontend/test-results/stage43-final'; out=root/'tools/.runtime/comparison-20261008/stage43-comparison.json'
files={side: next(base.glob('account-audit-*'+side)) for side in ('prototype','migration')}
read=lambda side,name:json.loads((files[side]/name).read_text('utf8'))
a,b=(read(side,'file.json') for side in files)
assert a['raw'][0]==b['raw'][0] and len(a['raw'][0])==24
assert len(a['raw'])==len(b['raw'])==14
assert a['types']==b['types']
ident={s:read(s,'identities.json') for s in files}
def normalize(value,side):
    if isinstance(value,str):
        return value.replace('/api/users/'+ident[side]['member']+'/', '/api/users/<member>/')
    return value
for i,(ar,br) in enumerate(zip(a['raw'][1:],b['raw'][1:]),1):
    for col in range(24):
        if col in (0,22): continue
        assert normalize(ar[col],'prototype')==normalize(br[col],'migration'),(i,col,ar[col],br[col])
        assert (ar[col] is None)==(br[col] is None)
ta,tb=(read(s,'table.json') for s in files)
assert len(ta)==len(tb)==13
for ar,br in zip(ta,tb): assert ar[:1]+ar[2:]==br[:1]+br[2:], (ar,br)
result={'accountAuditRows':13,'workbookColumns':24,'sameBusinessColumns':22,'typesAndNullsEqual':True,'excludedGeneratedColumns':['time','requestId'],'resolvedIndependentIdentity':['failed reset route member id'], 'orderedVisibleTableRows':13,'visibleColumnsCompared':11,'completeGoal':False}
out.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8');print(json.dumps(result,ensure_ascii=False))
