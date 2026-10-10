"""Compare the final real-browser factory states without sorting displayed rows or replacing text."""
from datetime import datetime, timedelta
from pathlib import Path
import json
import sys

root = Path.cwd()
base = root / 'frontend/test-results' / (sys.argv[1] if len(sys.argv) > 1 else 'stage45-final4')
left = json.loads(next(base.glob('*prototype/factory-states.json')).read_text('utf8'))
right = json.loads(next(base.glob('*migration/factory-states.json')).read_text('utf8'))
assert [x['name'] for x in left] == [x['name'] for x in right]
result = {'states': [], 'geometryDifferences': [], 'paintDifferences': [], 'businessRowsEqual': True,
          'timeCellsMatchOwnStoredValues': True, 'geometryGroups': list(range(14))}
for a, b in zip(left, right):
    name = a['name']
    assert [r[:3] for r in a['rows']] == [r[:3] for r in b['rows']], (name, 'ordered business cells')
    for java, item in [(False, a), (True, b)]:
        records = {r['code' if java else 'factory_code']: r for r in item['records']}
        assert len(item['rows']) == len(records), (name, 'complete catalog')
        for row in item['rows']:
            record = records[row[0]]
            stored = datetime.fromisoformat(record['updated_at'])
            if java:
                stored += timedelta(hours=8)
            assert row[3] == stored.strftime('%Y-%m-%d %H:%M:%S'), (name, 'time cell', row[0])
    for i in range(14):
        p = [v['rect'] for v in a['data'][i]]
        q = [v['rect'] for v in b['data'][i]]
        if p != q:
            result['geometryDifferences'].append({'state': name, 'group': i, 'source': p, 'target': q})
    # 原型外层容器与Antd容器的继承字号不等同实际绘制文本。核对表头、按钮、来源标签及提示文字。
    for i in (2, 13, 14, 15):
        properties = ('text', 'color', 'background', 'fontSize') if i != 15 else ('text', 'color', 'fontSize')
        p = [{k: v[k] for k in properties} for v in a['data'][i]]
        q = [{k: v[k] for k in properties} for v in b['data'][i]]
        if p != q:
            result['paintDifferences'].append({'state': name, 'group': i, 'source': p, 'target': q})
    result['states'].append({'name': name, 'rows': len(a['rows']), 'orderedBusinessCellsEqual': True,
                             'storedTimesCheckedForBothProjects': len(a['rows']) + len(b['rows'])})
destination = root / 'tools/.runtime/comparison-20261008/stage45-factory-comparison.json'
destination.write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'states': len(left), 'businessRowsEqual': True,
                  'geometryDifferences': len(result['geometryDifferences']),
                  'paintDifferences': len(result['paintDifferences'])}, ensure_ascii=False))
if result['geometryDifferences'] or result['paintDifferences']:
    for k in ('geometryDifferences','paintDifferences'):
        for difference in result[k][:8]:
            print(k, json.dumps(difference, ensure_ascii=False))
    raise SystemExit(1)
