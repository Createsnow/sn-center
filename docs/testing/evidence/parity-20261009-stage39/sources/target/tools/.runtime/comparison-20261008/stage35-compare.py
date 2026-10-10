"""Compare browser evidence without normalizing values or changing frozen stage32."""
from pathlib import Path
import json

root = Path.cwd()
directory = root / 'frontend/test-results/stage35-verified-ui'
out = {'tables': [], 'layouts': [], 'details': [], 'files': [], 'realRules': []}
for family in ('audit-page-', 'audit-files-'):
    old = next(p for p in directory.iterdir() if p.is_dir() and p.name.startswith(family) and p.name.endswith('prototype'))
    new = next(p for p in directory.iterdir() if p.is_dir() and p.name.startswith(family) and p.name.endswith('migration'))
    for file in sorted(old.glob('*.json')):
        a = json.loads(file.read_text('utf8'))
        b = json.loads((new / file.name).read_text('utf8'))
        if 'cells' in a:
            assert a['cells'] == b['cells'], file.name
            out['tables'].append({'case': file.name, 'rows': len(a['cells']), 'allRawCellsAndOrderEqual': True})
            assert a['layout'] == b['layout'], (file.name, 'layout')
            out['layouts'].append({'case': file.name, 'equal': True})
            if a['details']:
                assert a['details'] == b['details'], (file.name, 'details')
                out['details'].append({'case': file.name, 'cells': len(a['details']), 'textGeometryAndStylesEqual': True})
        if 'raw' in a:
            for key in ('raw', 'types', 'sheet', 'filename'):
                assert a[key] == b[key], (family, file.name, key)
            out['files'].append({'case': family + file.name, 'rows': len(a['raw']) - 1, 'columns': len(a['raw'][0]), 'allCellValuesTypesOrderSheetAndFilenameEqual': True, 'sourceSha256': a['sha256'], 'migrationSha256': b['sha256']})
        if 'syntheticRowsRemoved' in a:
            assert a['syntheticRowsRemoved'] == b['syntheticRowsRemoved'] == 54
            assert a['businessAndAccountSecurityUnchanged'] and b['businessAndAccountSecurityUnchanged']

for old in sorted(directory.glob('audit-rules-*-prototype')):
    new = directory / (old.name[:-len('prototype')] + 'migration')
    a = json.loads((old / 'rules-file.json').read_text('utf8'))
    b = json.loads((new / 'rules-file.json').read_text('utf8'))
    assert a['sheet'] == b['sheet'] == 'audit'
    assert a['raw'][0] == b['raw'][0] and len(a['raw'][0]) == 24
    assert a['types'] == b['types']
    # Independent actions have different timestamps, configured admin names and trace IDs.
    # Keep and report those differences; all other 21 columns must match in original order.
    differing = [i for i in range(24) if [r[i] for r in a['raw']] != [r[i] for r in b['raw']]]
    assert set(differing) <= {0, 2, 22}, differing
    for col in set(range(24)) - {0, 2, 22}:
        assert [r[col] for r in a['raw']] == [r[col] for r in b['raw']], (old.name, col)
    assert len(a['raw']) == len(b['raw'])
    assert all(r[14] is None for r in a['raw'][1:] + b['raw'][1:])
    out['realRules'].append({'case': old.name[:-len('-prototype')], 'rows': len(a['raw']) - 1, 'matchingOrderedColumnCount': 21, 'allTypesEqual': True, 'headerAndSheetEqual': True, 'differingColumns': [a['raw'][0][i] for i in differing], 'sourceSha256': a['sha256'], 'migrationSha256': b['sha256'], 'fullFilesEqual': False})
assert len(out['tables']) == len(out['layouts']) == 15
assert sum(d['cells'] for d in out['details']) == 20
assert len(out['files']) == 4 and len(out['realRules']) == 2
assert sorted(r['rows'] for r in out['realRules']) == [2, 5]
out['auditLayout'] = []
a = next(directory.glob('audit-layout-*-prototype'))
b = next(directory.glob('audit-layout-*-migration'))
keys = ['surface', 'toolbar', 'headers', 'cells', 'pager', 'total', 'size', 'prev', 'pages', 'next', 'date', 'dateInput', 'dateIcon', 'separator']
for file in sorted(a.glob('*.json')):
    if file.name == 'baseline-preserved.json':
        continue
    source = json.loads(file.read_text('utf8'))
    migration = json.loads((b / file.name).read_text('utf8'))
    groups = {}
    for key in keys:
        old = [[v[field] for field in ['x', 'y', 'width', 'height']] for v in source[key]]
        new = [[v[field] for field in ['x', 'y', 'width', 'height']] for v in migration[key]]
        assert old == new and old, (file.name, key, old, new)
        groups[key] = len(old)
    assert [v['text'] for v in source['headers']] == [v['text'] for v in migration['headers']]
    assert [v['text'] for v in source['cells']] == [v['text'] for v in migration['cells']]
    assert [v['text'] for v in source['dateInput']] == [v['text'] for v in migration['dateInput']]
    assert [v['color'] for v in source['dateInput']] == [v['color'] for v in migration['dateInput']]
    out['auditLayout'].append({'case': file.name, 'exactGeometryGroups': groups, 'tableTextsAndDateValuesEqual': True, 'dateInputColorsEqual': True})
assert len(out['auditLayout']) == 9
(root / 'tools/.runtime/comparison-20261008/stage35-comparison.json').write_text(json.dumps(out, ensure_ascii=False, indent=2), 'utf8')
print('15 table/layout pairs and 4 full XLSX pairs match; 2 real-rule file pairs match 21 ordered columns and all types. Time, configured admin name and trace ID differences retained.')

print('9 audit layout pairs: all 14 component groups match exact rectangles, including 12 columns, date inputs and narrow pagination.')

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
(root/'tools/.runtime/comparison-20261008/stage35-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('4 real authentication file pairs: 22 ordered columns/all types equal, actual timestamps/request IDs retained; 14 real facts per side.')
print('14 actor rows have identical character rectangles/wrapping; unknown/locked messages painted before and after capture, remaining login styling differences retained.')
