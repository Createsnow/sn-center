"""Read each isolated audit history independently; do not rewrite unequal histories."""
from collections import defaultdict
from datetime import datetime, timezone, timedelta
from pathlib import Path
import json
import pymysql

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
private = root / 'deploy/.secrets/comparison-20261008'
actions = {
    'sn:generation:execute': 'SN_GENERATE', 'sn:allocation:execute': 'SN_ALLOCATE',
    'sn:acquire:take': 'SN_ACQUIRE', 'sn:print:execute': 'SN_PRINT',
    'sn:acquire:callback': 'SN_CALLBACK',
}
today = datetime.now(timezone(timedelta(hours=8))).replace(hour=0, minute=0, second=0, microsecond=0)
result = {'day': today.date().isoformat(), 'systems': {}}
for kind in ('java', 'python'):
    java = kind == 'java'
    start = (today.astimezone(timezone.utc) if java else today).replace(tzinfo=None)
    end = start + timedelta(days=1)
    with pymysql.connect(host='127.0.0.1', port=33308, user=kind+'_compare',
                        password=(private/('mysql-'+kind)).read_text().strip(),
                        database='sn_'+kind+'_compare', cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
        c.execute('SELECT action,factory_code,' + ('pi' if java else 'pi_no') + ' pi,' +
                  ('quantity' if java else 'qty') + ' quantity,created_at FROM sn_audit WHERE result=%s AND created_at>=%s',
                  ('SUCCESS' if java else 'OK', start))
        rows = [r for r in c.fetchall() if (actions.get(r['action']) if java else r['action']) in actions.values()]
    all_totals, bound_totals, groups = defaultdict(int), defaultdict(int), defaultdict(lambda: {'rows': 0, 'quantity': 0})
    future = [r for r in rows if r['created_at'] >= end]
    for row in rows:
        action = actions[row['action']] if java else row['action']
        # Keep the source's lower-only date boundary visible; present-day evidence has no future rows.
        if java and row['created_at'] >= end:
            continue
        quantity = int(row['quantity'] or 0)
        all_totals[action] += quantity
        if row['factory_code'] == 'CMP-A':
            bound_totals[action] += quantity
        group = groups[(action, row['factory_code'] or '', row['pi'] or '')]
        group['rows'] += 1
        group['quantity'] += quantity
    result['systems'][kind] = {
        'allFactories': dict(sorted(all_totals.items())), 'factoryA': dict(sorted(bound_totals.items())),
        'matchingSuccessfulFacts': len(rows), 'futureDatedFacts': len(future),
        'groups': [dict(action=k[0], factory=k[1], pi=k[2], **v) for k,v in sorted(groups.items())],
    }
java = result['systems']['java']; python = result['systems']['python']
result['quantityDifferenceJavaMinusPython'] = {
    scope: {action: java[scope].get(action, 0)-python[scope].get(action, 0)
            for action in sorted(set(java[scope]) | set(python[scope]))}
    for scope in ('allFactories', 'factoryA')
}
result['scope'] = 'Current stored successful audit facts only; unequal independent historical runs are retained. Future-date boundary semantics differ and are not proved equal.'
(run/'stage38-statistics.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({k: {key: value for key, value in v.items() if key != 'groups'} for k,v in result['systems'].items()}, ensure_ascii=False))
