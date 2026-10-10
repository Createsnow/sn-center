from pathlib import Path
import json
root=Path.cwd()
directory=root/'frontend/test-results/stage43-final'
out={}
# Authentication actions happened independently: preserve actual time/request ID values.
out['authentication'] = []
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
(root/'tools/.runtime/comparison-20261008/stage43-auth-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('4 real authentication file pairs: 22 ordered columns/all types equal, actual timestamps/request IDs retained; 14 real facts per side.')
print('14 actor rows have identical character rectangles/wrapping; unknown/locked messages painted before and after capture, remaining login styling differences retained.')
