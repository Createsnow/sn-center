"""Compare final measured UI states and independent stored audit totals."""
from pathlib import Path
import json
root=Path.cwd()
directory=root/'frontend/test-results/stage38-final-ui'
run=root/'tools/.runtime/comparison-20261008'
statistics=json.loads((run/'stage38-statistics.json').read_text('utf8'))
out={'dashboard':[]}
layout=root/'frontend/test-results/stage38-narrow-delivery'
a=next(layout.glob('dashboard-layout-*-prototype'));b=next(layout.glob('dashboard-layout-*-migration'))
for file in sorted(a.glob('*.json')):
 source=json.loads(file.read_text('utf8'));target=json.loads((b/file.name).read_text('utf8'))
 if file.name=='narrow-navigation-probe.json':
  out['collapsedMenuAccessibleNameProbe']={'prototype':source,'migration':target}
  continue
 if file.name=='dashboard-response.json':
  mapping={'TAKE':'SN_ACQUIRE','ALLOCATE':'SN_ALLOCATE','GENERATE':'SN_GENERATE','PRINT':'SN_PRINT','CALLBACK':'SN_CALLBACK'}
  assert source['today_actions']==statistics['systems']['python']['factoryA']
  assert {mapping[row['name']]:int(row['quantity']) for row in target['data']['today']}==statistics['systems']['java']['factoryA']
  out['todayApiMatchesOwnStoredFacts']=True
 elif file.name=='actions.json':
  assert source==target and source['shortcutNavigations']==12 and source['narrowShortcutNavigations']==6 and source['narrowMenuReturns']==3
  out['dashboardActions']=source
 else:
  assert source==target,(file.name,[key for key in source if source[key]!=target[key]])
  assert len(source)==29
  out['dashboard'].append({'case':file.name,'all29GroupsEqual':True,'visibleTextCharacterRectanglesEqual':True})
assert len(out['dashboard'])==15
layout_directory=directory
a=next(layout_directory.glob('login-layout-*-prototype'));b=next(layout_directory.glob('login-layout-*-migration'))
out['loginLayout']=[]
for file in sorted(a.glob('*.json')):
    source=json.loads(file.read_text('utf8'));target=json.loads((b/file.name).read_text('utf8'))
    assert source==target,(file.name, [key for key in source if source[key]!=target[key]])
    out['loginLayout'].append({'case':file.name,'geometryStyleAndIconGroupsEqual':list(source),'groupCount':len(source)})
assert len(out['loginLayout'])==18
assert all(row['groupCount']==15 for row in out['loginLayout'])
(root/'tools/.runtime/comparison-20261008/stage38-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('18 paired login states: all 15 measured groups match geometry, sampled styles and icon paths. This does not assert full pixel equality.')

out['passwordPages']=[]
out['passwordActions']=[]
out['passwordSuccess']=[]
for a in directory.glob('password-page-*-prototype'):
    b=directory/(a.name[:-len('prototype')]+'migration')
    for file in sorted(a.glob('*.json')):
        source=json.loads(file.read_text('utf8'));target=json.loads((b/file.name).read_text('utf8'))
        if file.name=='actions.json':
            assert source==target and source['passwordRequests']==1
            out['passwordActions'].append(source)
        elif file.name=='success.json':
            for sample in (source,target):
                for time in ('before','after'):
                    assert sample[time]['opacity']==1 and sample[time]['y']>=0
            assert source['after']==target['after'],(a.name,file.name,source,target)
            out['passwordSuccess'].append({'case':a.name,'paintedBeforeAndAfterScreenshot':True,'stableMessageGeometryAndTextEqual':True})
        else:
            assert source==target,(a.name,file.name,[key for key in source if source[key]!=target[key]])
            assert len(source)==15
            out['passwordPages'].append({'case':a.name+'/'+file.name,'all15GeometryStyleTextIconGroupsEqual':True,'warningCharacterRectanglesEqual':True})
assert len(out['passwordPages'])==33
assert len(out['passwordActions'])==len(out['passwordSuccess'])==3
(root/'tools/.runtime/comparison-20261008/stage38-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('33 password page pairs match all 15 measured groups and warning characters; three real save/logout/cancel flows and three success message pairs verified.')

print('15 dashboard/header pairs match all 29 measured groups and visible text; each real API matches its own unchanged audit history.')
