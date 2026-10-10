"""Compare paired real UI/MES observations; protocol differences remain explicit."""
from pathlib import Path
import json

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'

def pair(group):
    folder = root / 'frontend/test-results' / ('stage54-' + group)
    values = {}
    for kind in ('prototype', 'migration'):
        paths = list(folder.glob('*-' + kind + '/observations.json'))
        assert len(paths) == 1
        values[kind] = json.loads(paths[0].read_text('utf8'))
        assert not values[kind]['errors']
    return values['prototype'], values['migration']

left, right = pair('mes2')
assert left['pi'] == right['pi'] and left['sns'] == right['sns']
states = []
assert len(left['facts']) == len(right['facts'])
for a, b in zip(left['facts'], right['facts']):
    assert a['stage'] == b['stage']
    if a['stage'] != 'audit':
        assert a['observed'] == b['observed']
        states.append(a)
assert len(states) == 7
observations = []
r = [v for v in right['mesObservations'] if v['stage'] != 'service-identity']
assert len(left['mesObservations']) == len(r)
for a, b in zip(left['mesObservations'], r):
    normalized = lambda v: {k: w for k, w in v.items() if k not in ('outcomes', 'immutableReplayChecked')}
    assert normalized(a) == normalized(b), a['stage']
    if 'outcomes' in a:
        failures = [{'sn': v['sn'], 'reason': v['outcome']} for v in b['outcomes'] if v['outcome'] not in ('UPDATED', 'ALREADY_PRINTED')]
        assert a['outcomes'] == failures
        assert b['immutableReplayChecked']
    observations.append(normalized(a))
assert len(observations) == 11
functional_left, functional_right = pair('functional-final')
assert functional_left == functional_right
pages = [v['name'] for v in functional_left['facts'] if v['stage'] == 'page-entry']
assert len(pages) == 11
result = {'mesStatePairs': states, 'mesBusinessPairs': observations,
          'mesWireProtocolIdentical': False,
          'mesProtocolNote': 'Approved separate service identity, durable tasks, operation UUID and cursor protocol; source only returns skipped callbacks, migration stores all item outcomes.',
          'functionalFacts': functional_left['facts'], 'pageEntriesPerSystem': pages,
          'browserErrors': [], 'passed': True}
(run / 'stage54-comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'mesStatePairs': len(states), 'mesBusinessPairs': len(observations), 'pageEntriesPerSystem': len(pages), 'functionalFacts': len(functional_left['facts']), 'passed': True}))
