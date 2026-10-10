"""Check actual focused UI flows, scrolling and persisted schedule values."""
from pathlib import Path
import json,sys
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';group=sys.argv[1] if len(sys.argv)>1 else 'ui5'
folder=root/'frontend/test-results'/('stage53-'+group)
values={k:json.loads(next(folder.glob('order-focus*-'+k+'/observations.json')).read_text('utf8')) for k in ('prototype','migration')}
a,b=values['prototype'],values['migration'];assert a['errors']==b['errors']==[]
assert len(a['states'])==len(b['states'])==27
pairs=[];differences=[]
scope={'opened','escape','switch-edited','cancel','close-button','backdrop','narrow-open','wide-reopened','keyboard-up','keyboard-down','mouse-step-up','mouse-step-down','step-minimum','step-maximum','wheel-bottom','opened-after-scroll','escape-after-scroll','saved-120','saved-1440'}
for p,m in zip(a['states'],b['states']):
    name=p['label'];assert name==m['label']
    row={'label':name,'prototypeScroll':p['scroll'],'migrationScroll':m['scroll'],'scrollEqual':p['scroll']==m['scroll'],'heightEqual':p['height']==m['height'] and p['scrollHeight']==m['scrollHeight'],'prototypeFocusTag':p['focus']['tag'],'migrationFocusTag':m['focus']['tag']}
    assert p['schedule'][0]['enabled']==m['schedule'][0]['enabled']==0
    assert p['schedule'][0]['interval_minutes']==m['schedule'][0]['interval_minutes']
    if name in scope:row['focusTagEqual']=p['focus']['tag']==m['focus']['tag']
    row['controlGeometry']={str(i):{'prototype':[v['rect'] for v in p['groups'][i]],'migration':[v['rect'] for v in m['groups'][i]]} for i in (0,1,3,4,5)}
    row['controlBoxesEqual']=all(v['prototype']==v['migration'] for v in row['controlGeometry'].values())
    if not row['scrollEqual'] or not row['heightEqual'] or row.get('focusTagEqual') is False or not row['controlBoxesEqual']:differences.append(row)
    pairs.append(row)
result={'pairs':pairs,'differences':differences,'scope':'Chinese administrator, 1440x960 and narrow-to-wide reopening at 768x960, dedicated synthetic database; semantic focus flow, not equality of framework DOM nodes.'}
(run/f'stage53-{group}-focus-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'pairs':len(pairs),'differences':len(differences)}))
assert not differences,differences
