"""Compare actual UI exports and visible cells; preserve per-system times and request IDs."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import json
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';main=root/'frontend/test-results/stage48-ui3';filters=root/'frontend/test-results/stage48-filters'
folders={k:next(main.glob('*-'+k)) for k in ('prototype','migration')}
result={'files':{},'filters':{},'scheduledFactories':{}}
normalize=lambda row:[v for i,v in enumerate(row) if i not in (0,22)]
for label,count in [('manual',7),('settings',3),('system',3)]:
    evidence={k:json.loads((d/(label+'-evidence.json')).read_text('utf8')) for k,d in folders.items()}
    a,b=evidence.values()
    assert len(a['parsed']['raw'])==len(b['parsed']['raw'])==count+1
    assert a['parsed']['raw'][0]==b['parsed']['raw'][0]
    assert [normalize(r) for r in a['parsed']['raw'][1:]]==[normalize(r) for r in b['parsed']['raw'][1:]],label
    assert a['parsed']['types']==b['parsed']['types'],label
    assert [[v for i,v in enumerate(row) if i!=1] for row in a['table']]==[[v for i,v in enumerate(row) if i!=1] for row in b['table']],label
    for kind,e in evidence.items():
        for item,cells in zip(e['items'],e['parsed']['raw'][1:],strict=True):
            expected=item.get('created_at') if kind=='prototype' else datetime.fromisoformat(item['createdAt'].replace('Z','+00:00')).astimezone(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')
            assert cells[0]==expected,(label,kind,'time')
            assert cells[22]==item.get('trace_id' if kind=='prototype' else 'traceId'),(label,kind,'trace')
    result['files'][label]={'rows':count,'columns':24,'identicalBusinessColumns':22,'allCellTypesAndNullsEqual':True,'individualTimesAndTraceIdsVerified':True,'visibleTableBusinessCellsEqual':True}
for state in ('09-scheduled-success','11-scheduled-recovered'):
    facts={}
    for kind,d in folders.items():
        db=json.loads((d/(state+'.json')).read_text('utf8'))
        rows=[r for r in db['audits'] if r['operator']=='system' and r['action'] in ('FACTORY_SYNC','sn:factory:sync')]
        r=rows[-1]
        facts[kind]=[r['operator'],r['operator_name'],r['source'],r.get('role') if kind=='prototype' else 'admin' if json.loads(r['roles'])==['SN_HQ'] else None,r.get('qty') if kind=='prototype' else r['quantity'],r.get('detail') if kind=='prototype' else r['detail_text'],r['trace_id']]
    assert facts['prototype']==facts['migration'],(state,facts)
    result['scheduledFactories'][state]=facts['prototype']
f={k:json.loads(next(filters.glob('*-'+k)).joinpath('filters.json').read_text('utf8')) for k in ('prototype','migration')}
for a,b in zip(f['prototype'],f['migration'],strict=True):
    assert a['label']==b['label']
    assert [[v for i,v in enumerate(row) if i!=1] for row in a['table']]==[[v for i,v in enumerate(row) if i!=1] for row in b['table']],a['label']
    result['filters'][a['label']]={'rows':len(a['items']),'visibleBusinessCellsEqual':True}
result['remaining']='Scheduled internal SQL error text, API validation semantics, complete K3 error families and full P01-P16 UI matrix remain unverified or different.'
(run/'stage48-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('3 pairs of real Excel files: 13 rows / 24 columns, 22 business values and all types equal; own times and trace IDs verified; 5 paired filters equal.')
