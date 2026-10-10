"""Compare actual UI responses, published business rows, and visible audit facts."""
from pathlib import Path
import json

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
folder = root / 'frontend/test-results/stage50-ui3'
data = {}
for kind in ('prototype', 'migration'):
    path = next(folder.glob('*-' + kind))
    observed = json.loads((path / 'observations.json').read_text('utf8'))
    assert observed['pageErrors'] == []
    data[kind] = {
        'observations': observed['observations'],
        'audits': json.loads((path / 'audit-visible-details.json').read_text('utf8')),
    }

def business_rows(kind, observed):
    if kind == 'prototype':
        return sorted([
            r['bill_no'], r['line_seq'], r['customer_number'], r['po'], r['pi'],
            r['prd_org_name'], r['material_number'], r['material_name'], r['qty'],
            r['status'], r['demand_bill_no'],
        ] for r in observed['entries'])
    bills = {r['id']: r for r in observed['orders']}
    result = []
    for line in observed['entries']:
        bill = bills[line['bill_id']]
        if not bill['active'] or not line['active']:
            continue
        result.append([
            bill['bill_no'], line['ordinal'], bill['customer'], bill['po'], bill['pi'],
            bill['org_name'], line['material'], line['material_name'], line['qty'],
            line['status'], line['demand_bill_no'],
        ])
    return sorted(result)

def audit_fact(kind, record):
    r = record['item']
    prototype = kind == 'prototype'
    assert r['action'] == ('ORDER_SYNC' if prototype else 'sn:order:sync')
    assert r['result'] == ('FAIL' if prototype else 'FAILED')
    return [r['operator'], r['operator_name' if prototype else 'operatorName'],
            r['source'], r['detail'], r['error_msg' if prototype else 'errorMessage'],
            r['bill_no' if prototype else 'billNo'], r['qty' if prototype else 'quantity']]

pairs = []
differences = []
left = data['prototype']['observations']
right = data['migration']['observations']
assert len(left) == len(right) == 64
for p, m in zip(left, right):
    label = p['label']
    assert label == m['label']
    response = (p['status'], p['message']) == (m['status'], m['message'])
    rows = business_rows('prototype', p) == business_rows('migration', m)
    traffic = [[r['operation'], r['start']] for r in p['traffic']] == [[r['operation'], r['start']] for r in m['traffic']]
    # The stable source-identity protocol may have different Login/Query counts; page offsets are exact.
    if 'later-page' in label:
        for observed in (p, m):
            assert [r['start'] for r in observed['traffic'] if r['operation'] == 'orders'] == [0, 2000]
    pairs.append({'label': label, 'responseEqual': response, 'businessRowsEqual': rows,
                  'activeRows': len(business_rows('prototype', p)), 'wireOperationsEqual': traffic})
    if not response or not rows:
        differences.append({'label': label, 'responseEqual': response, 'businessRowsEqual': rows})
facts = {}
for kind, value in data.items():
    assert len(value['audits']) == 39
    facts[kind] = sorted(json.dumps(audit_fact(kind, r), ensure_ascii=False) for r in value['audits'])
assert facts['prototype'] == facts['migration'], 'audit facts differ'
result = {'pairs': pairs, 'differences': differences, 'matchingAuditFacts': 39,
          'expandedDetailsPerSystem': 39, 'businessColumnsPerRow': 11,
          'scope': 'three languages, administrator, 1440x960; not the complete UI matrix'}
(run / 'stage50-comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf8')
assert not differences, differences
print('64 UI response/business snapshot pairs and 39 expanded audit facts match.')
