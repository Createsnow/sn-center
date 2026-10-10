"""Compare visible return-state, pointer scroll observations and actual text ranges."""
from pathlib import Path
import json

root=Path.cwd();run=root/'tools/.runtime/comparison-20261008'
def reports(group,test):
    return {k:json.loads(next((root/'frontend/test-results'/('stage53-'+group)).glob(test+'*-'+k+'/observations.json')).read_text('utf8')) for k in ('prototype','migration')}
result={}
for group in ('revisit-before','revisit-after'):
    data=reports(group,'order-revisit');pairs=[]
    for p,m in zip(data['prototype']['observations'],data['migration']['observations']):
        assert p['label']==m['label']
        row={'label':p['label'],'prototype':{'counts':p['values'][1:],'amount':p['amount'],'unit':p['unit']},
             'migration':{'counts':m['values'][1:],'amount':m['amount'],'unit':m['unit']}}
        row['equal']=row['prototype']==row['migration'];pairs.append(row)
    assert len(pairs)==3
    result[group]=pairs
assert sum(not p['equal'] for p in result['revisit-before'])==2
assert all(p['equal'] for p in result['revisit-after'])

scroll=reports('ui-final','order-scroll');pairs=[]
for p,m in zip(scroll['prototype']['records'],scroll['migration']['records']):
    assert p['mode']==m['mode']
    row={'mode':p['mode'],'prototypeScrollValues':sorted(set(f['scroll'] for f in p['frames'])),
         'migrationScrollValues':sorted(set(f['scroll'] for f in m['frames']))}
    if 'mouse' in row['mode']:assert row['prototypeScrollValues']==row['migrationScrollValues']==[0],row
    pairs.append(row)
assert len(pairs)==12
result['scrollPairs']=pairs

data=reports('ui-final','order-interactions');differences=[];checks=0;line_heights=[]
assert data['prototype']['errors']==data['migration']['errors']==[]
for p,m in zip(data['prototype']['states'],data['migration']['states']):
    assert p['label']==m['label']
    for group in ('details','lineCells'):
        pc,mc=p['geometry'][group],m['geometry'][group]
        assert len(pc)==len(mc)
        for i,(a,b) in enumerate(zip(pc,mc)):
            checks+=1
            left=[{k:v for k,v in x.items() if k!='lineHeight'} for x in a['paint']]
            right=[{k:v for k,v in x.items() if k!='lineHeight'} for x in b['paint']]
            if left!=right:differences.append({'label':p['label'],'group':group,'index':i,'prototype':left,'migration':right})
            if [x['lineHeight'] for x in a['paint']]!=[x['lineHeight'] for x in b['paint']]:
                line_heights.append({'label':p['label'],'group':group,'index':i,'prototype':[x['lineHeight'] for x in a['paint']],'migration':[x['lineHeight'] for x in b['paint']]})
result['paint']={'cellPairs':checks,'differences':differences,'textNodeLineHeightDifferences':line_heights,
                 'scope':'Visible nonblank text nodes, exact Range glyph rectangles, family, size, weight, spacing and color. Node line-height differences retained separately; no screenshot-pixel equality claim.'}
(run/'stage53-extra-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'revisitPairs':3,'scrollPairs':len(pairs),'nativePointerPairs':sum('mouse' in p['mode'] for p in pairs),'textCellPairs':checks,'textCellDifferences':len(differences),'textLineHeightDifferences':len(line_heights)}))
