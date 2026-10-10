"""Compare actual authentication files and login geometry/styles without normalization."""
from pathlib import Path
import json
root=Path.cwd()
directory=root/'frontend/test-results/stage37-final-ui'
# Authentication actions happened independently: preserve actual time/request ID values.
out = {'authentication': []}
for old in sorted(directory.glob('audit-auth-*-prototype')):
    new = directory / (old.name[:-len('prototype')] + 'migration')
    for file in old.glob('*-file.json'):
        a=json.loads(file.read_text('utf8'));b=json.loads((new/file.name).read_text('utf8'))
        assert a['raw'][0]==b['raw'][0] and len(a['raw'][0])==24
        assert a['sheet']==b['sheet']=='audit'
        assert a['types']==b['types'],(file.name,'types')
        differing=[i for i in range(24) if [v[i] for v in a['raw']]!=[v[i] for v in b['raw']]]
        assert set(differing)<={0,22},(file.name,differing)
        for col in set(range(24))-{0,22}:
            assert [v[col] for v in a['raw']]==[v[col] for v in b['raw']],(file.name,col)
        table=file.name.replace('-file.json','-table.json')
        ta=json.loads((old/table).read_text('utf8'));tb=json.loads((new/table).read_text('utf8'))
        assert [row[2:] for row in ta]==[row[2:] for row in tb],(file.name,'DOM rows excluding time and expansion')
        assert all(row[14] is None for row in a['raw'][1:]+b['raw'][1:])
        out['authentication'].append({'case':file.name,'rows':len(a['raw'])-1,'orderedMatchingColumns':22,'typesEqual':True,'sheetAndHeaderEqual':True,'orderedDomCellsExceptTimeEqual':True,'differingColumns':[a['raw'][0][i] for i in differing],'sourceSha256':a['sha256'],'migrationSha256':b['sha256'],'fullFileEquality':False})
assert sorted(v['rows'] for v in out['authentication'])==[1,2,5,6]
out['authenticationActorLayout'] = []
out['authenticationMessagePaint'] = []
for old in sorted(directory.glob('audit-auth-*-prototype')):
    new = directory / (old.name[:-len('prototype')] + 'migration')
    for file in sorted(old.glob('*-actors.json')):
        a = json.loads(file.read_text('utf8'))
        b = json.loads((new / file.name).read_text('utf8'))
        assert a == b and a, (file.name, 'actor text rectangles')
        out['authenticationActorLayout'].append({'case': file.name, 'rows': len(a), 'characterRectanglesAndWrappingEqual': True})
    for file in sorted(old.glob('*-paint.json')):
        a = json.loads(file.read_text('utf8'))
        b = json.loads((new / file.name).read_text('utf8'))
        for sample in (a, b):
            for timing in ('before', 'after'):
                assert sample[timing]['opacity'] == 1 and sample[timing]['top'] >= 0
                assert sample[timing]['bottom'] > sample[timing]['top']
        assert a['before']['text'] == a['after']['text'] == b['before']['text'] == b['after']['text']
        out['authenticationMessagePaint'].append({'case': file.name, 'bothVisibleBeforeAndAfterCapture': True, 'messageEqual': True, 'geometryEqual': a == b})
assert len(out['authenticationActorLayout']) == 4
assert sum(v['rows'] for v in out['authenticationActorLayout']) == 14
assert len(out['authenticationMessagePaint']) == 2
(root/'tools/.runtime/comparison-20261008/stage37-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('4 real authentication file pairs: 22 ordered columns/all types equal, actual timestamps/request IDs retained; 14 real facts per side.')
print('14 actor rows have identical character rectangles/wrapping; unknown/locked messages painted before and after capture, these samples do not establish all login states.')

layout_directory=directory
a=next(layout_directory.glob('login-layout-*-prototype'));b=next(layout_directory.glob('login-layout-*-migration'))
out['loginLayout']=[]
for file in sorted(a.glob('*.json')):
    source=json.loads(file.read_text('utf8'));target=json.loads((b/file.name).read_text('utf8'))
    assert source==target,(file.name, [key for key in source if source[key]!=target[key]])
    out['loginLayout'].append({'case':file.name,'geometryStyleAndIconGroupsEqual':list(source),'groupCount':len(source)})
assert len(out['loginLayout'])==18
assert all(row['groupCount']==15 for row in out['loginLayout'])
(root/'tools/.runtime/comparison-20261008/stage37-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
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
(root/'tools/.runtime/comparison-20261008/stage37-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('33 password page pairs match all 15 measured groups and warning characters; three real save/logout/cancel flows and three success message pairs verified.')
