"""Compare paired browser observations without treating scroll offsets as pixel equality."""
from pathlib import Path
from datetime import datetime, timedelta
import json, sys

root=Path.cwd(); run=root/'tools/.runtime/comparison-20261008'
label=sys.argv[1] if len(sys.argv)>1 else 'ui5'
folder=root/'frontend/test-results'/('stage53-'+label)
observations={k:json.loads(next(folder.glob('order-interactions*-'+k+'/observations.json')).read_text('utf8')) for k in ('prototype','migration')}
left,right=(observations[k] for k in ('prototype','migration'))
assert left['errors']==right['errors']==[]
assert len(left['states'])==len(right['states'])==(76 if label == 'ui6' else 77)
for kind,data in observations.items():
    assert len(data['writes'])==6
    for i,w in enumerate(data['writes']):
        assert w['status']==200
        assert w['request']=={'enabled':False, 'interval_minutes' if kind=='prototype' else 'intervalMinutes': [120,1440][i%2]}

pairs=[]; visual=[]; functional=[]; time_checks=0
groups=['dialog','title','labels','number','unit','hint','alert','buttons','switch','message']
for a,b in zip(left['states'],right['states']):
    assert a['label']==b['label']
    name=a['label']; pair={'label':name}
    for field in ('tooltips','message','amount'):
        pair[field+'Equal']=a[field]==b[field]
        if a[field]!=b[field]:functional.append({'label':name,'field':field,'prototype':a[field],'migration':b[field]})
    for kind,state in [('prototype',a),('migration',b)]:
        schedule=state['schedule'][0]
        assert schedule['enabled']==0 and schedule['next_run_at'] is None
        if state['geometry']['dialog']:
            text=state['geometry']['dialog'][0]['text']
            for field in ('last_run_at','updated_at'):
                stamp=datetime.fromisoformat(schedule[field])
                if kind=='migration':stamp+=timedelta(hours=8)
                assert stamp.strftime('%Y-%m-%d %H:%M:%S') in text,(kind,name,field)
                time_checks+=1
    assert a['schedule'][0]['interval_minutes']==b['schedule'][0]['interval_minutes']
    equal_boxes=True
    for group in groups:
        l=a['geometry'][group]; r=b['geometry'][group]
        assert len(l)==len(r),(name,group)
        for i,(p,m) in enumerate(zip(l,r)):
            if p['box']!=m['box']:
                equal_boxes=False
                visual.append({'label':name,'group':group,'index':i,'field':'box','prototype':p['box'],'migration':m['box']})
    pair['principalBoxesEqual']=equal_boxes
    if a['mainScroll']!=b['mainScroll']:
        visual.append({'label':name,'group':'main','field':'scrollTop','prototype':a['mainScroll'],'migration':b['mainScroll']})
    for group in ['tooltip','detailTitle','detailButton','details','lineCells','numberInput','unitText','alertText','messageText']:
        l=a['geometry'][group]; r=b['geometry'][group]
        if len(l)!=len(r):
            visual.append({'label':name,'group':group,'field':'count','prototype':len(l),'migration':len(r)})
            continue
        for i,(p,m) in enumerate(zip(l,r)):
            for field in ('box','font','color','background','padding','shadow','filter'):
                if p[field]!=m[field]:
                    visual.append({'label':name,'group':group,'index':i,'field':field,'prototype':p[field],'migration':m[field]})
    pairs.append(pair)

result={'run':label,'pairs':pairs,'functionalDifferences':functional,'visualDifferences':visual,
        'overflowPairs':sum('overflow' in v['label'] for v in pairs),
        'shortTextPairs':sum('short-no-tooltip' in v['label'] for v in pairs),
        'scrollClosesTooltipPairs':sum('scroll-closes-tooltip' in v['label'] for v in pairs),
        'successfulScheduleWritesPerSystem':6,'timestampChecksAgainstOwnDatabase':time_checks,
        'scope':'Administrator, three languages, 1440x960 and 768x960. Scroll/paint differences are reported, not normalized into a pass.'}
(run/('stage53-'+label+'-comparison.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
assert not functional,functional
print(json.dumps({'pairs':len(pairs),'functionalDifferences':len(functional),'principalBoxMismatches':sum(not x['principalBoxesEqual'] for x in pairs),'visualDifferences':len(visual),'timestampChecks':time_checks}))
