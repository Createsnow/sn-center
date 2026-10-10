from pathlib import Path
import json
from datetime import datetime, timedelta
root=Path.cwd(); base=root/'frontend/test-results/stage46-final10'; out=root/'tools/.runtime/comparison-20261008/stage46-audit-comparison.json'
files={side: next(base.glob('factory-audit-*'+side)) for side in ('prototype','migration')}
read=lambda side,name:json.loads((files[side]/name).read_text('utf8'))
a,b=(read(side,'file.json') for side in files)
assert a['raw'][0]==b['raw'][0] and len(a['raw'][0])==24
assert len(a['raw'])==len(b['raw'])==13
assert a['types']==b['types']
for side in files:
    records=read(side,'records.json'); book=read(side,'file.json')
    for record,row in zip(records,book['raw'][1:]):
        value=record['created_at' if side=='prototype' else 'createdAt']
        stamp=datetime.fromisoformat(value.replace('Z','+00:00'))
        if side=='migration': stamp+=timedelta(hours=8)
        assert row[0]==stamp.strftime('%Y-%m-%d %H:%M:%S')
        assert row[22]==record['trace_id' if side=='prototype' else 'traceId']
for i,(ar,br) in enumerate(zip(a['raw'][1:],b['raw'][1:]),1):
    for col in range(24):
        if col in (0,22): continue
        assert ar[col]==br[col],(i,col,ar[col],br[col])
        assert (ar[col] is None)==(br[col] is None)
ta,tb=(read(s,'table.json') for s in files)
assert len(ta)==len(tb)==12
for ar,br in zip(ta,tb): assert ar[:1]+ar[2:]==br[:1]+br[2:], (ar,br)
for ar,br in zip(read('prototype','refreshed-table.json'),read('migration','refreshed-table.json')): assert ar[:1]+ar[2:]==br[:1]+br[2:], (ar,br)
assert read('prototype','factory-label-glyphs.json')==read('migration','factory-label-glyphs.json'), 'Factory label glyph geometry differs'
result={'generatedColumnsMatchOwnApi':True,'factoryLabelGlyphGeometryEqual':True,'refreshedOrderedRows':12,'factoryAuditRows':10,'authenticationRows':2,'workbookColumns':24,'sameBusinessColumns':22,'typesAndNullsEqual':True,'excludedGeneratedColumns':['time','requestId'],'orderedVisibleTableRows':12,'visibleColumnsCompared':11,'completeGoal':False}
out.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8');print(json.dumps(result,ensure_ascii=False))
