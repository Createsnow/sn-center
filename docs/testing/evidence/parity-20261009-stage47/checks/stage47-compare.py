"""Compare independent transaction UI outcomes and real stored records; keep remaining audit differences explicit."""
from pathlib import Path
from datetime import timedelta
from decimal import Decimal
import json,pymysql

root=Path.cwd();run=root/'tools/.runtime/comparison-20261008'
base=root/'frontend/test-results/stage47-final8'
states={side:json.loads(next(base.glob('order-transactions*'+side+'/states.json')).read_text('utf8')) for side in ('prototype','migration')}
assert len(states['prototype'])==len(states['migration'])==13
times={}
private=root/'deploy/.secrets/comparison-20261008/stage47'
for kind in ('java','python'):
    with pymysql.connect(host='127.0.0.1',port=33308,user=kind+'_tx47',password=(private/('mysql-'+kind)).read_text().strip(),database='sn_'+kind+'_tx47',cursorclass=pymysql.cursors.DictCursor) as conn,conn.cursor() as c:
        c.execute('SELECT '+('code' if kind=='java' else 'factory_code')+' code,updated_at FROM sn_factory')
        times[kind]={r['code']:(r['updated_at']+(timedelta(hours=8) if kind=='java' else timedelta())).strftime('%Y-%m-%d %H:%M:%S') for r in c.fetchall()}
        c.execute('SHOW TRIGGERS');assert not [r for r in c.fetchall() if r['Trigger'].startswith('tx47_fault_')],kind

def orders(db,java):
    if java:
        bills={r['id']:r for r in db['orders'] if r['active']}
        return sorted((b['bill_no'],r['ordinal'],b['customer'],b['po'],b['pi'],r['material'],r['material_name'],str(Decimal(r['qty']).normalize()),r['status'],r['demand_bill_no'],b['org_name']) for r in db['entries'] if r['active'] for b in [bills[r['bill_id']]])
    return sorted((r['bill_no'],r['line_seq'],r['customer_number'],r['po'],r['pi'],r['material_number'],r['material_name'],str(Decimal(r['qty']).normalize()),r['status'],r['demand_bill_no'],r['prd_org_name']) for r in db['orders'])

out=[]
for a,b in zip(states['prototype'],states['migration']):
    label=a['label'];assert label==b['label']
    assert a['warnings']==b['warnings'],(label,'warnings')
    assert a['messages']==b['messages'],(label,'messages',a['messages'],b['messages'])
    assert a['database']['factories']==b['database']['factories'],(label,'factories')
    assert orders(a['database'],False)==orders(b['database'],True),(label,'lines')
    if label.endswith('orders'):
        assert a['rows']==b['rows'],(label,'visible ordered rows')
        keys=('text','x','y','width','height','padding','margin','fontSize','fontWeight','color','background','borderColor','boxShadow')
        assert [{k:x[k] for k in keys} for x in a['buttons']]==[{k:x[k] for k in keys} for x in b['buttons']],(label,'buttons')
    else:
        assert [x[:-19] for x in a['rows']]==[x[:-19] for x in b['rows']],(label,'factory visible business rows')
        for kind,state in [('python',a),('java',b)]:
            for text,record in zip(state['rows'],state['database']['factories']):
                assert text.startswith(record['code']+record['name']),label
                assert text[-19:]==times[kind][record['code']],(label,'stored update time')
    out.append({'label':label,'factories':len(a['database']['factories']),'orderLines':len(orders(a['database'],False)),
                'orderedVisibleBusinessRowsEqual':True,'warningsAndFeedbackEqual':True,'syncButtonGeometryEqual':label.endswith('orders')})

stages=[s for s in states['migration'] if s['label'].endswith('orders')]
for state in stages:
    row=state['database']['attempts'][-1]
    label=state['label']
    if label.startswith('factory-'):
        assert row['state']=='SUCCESS' and row['org_sync_failed']==1 and row['factory_version'] is None
        assert row['published_version']==row['base_version']+1
    elif label.startswith('order-'):
        assert row['state']=='FAILED' and row['factory_version']==row['base_version']+1 and row['published_version'] is None
    elif label.startswith('bill-only'):
        assert row['state']=='SUCCESS' and row['factory_version'] is None
    else:
        assert row['state']=='SUCCESS' and row['factory_version']==row['base_version']+1 and row['published_version']==row['base_version']+2
result={'states':out,'persistedFactoryReceiptsAndOrderOutcomesVerified':7,'faultTriggersRemoved':True,
        'factoryUpdateTimesByProject':times,
        'remainingAuditDifference':'ORDER_SYNC quantity/detail/bill fields and failed-operation audit visibility still differ; no claim of audit page parity in this stage.'}
(run/'stage47-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('13 paired states, order lines, visible rows, feedback, warnings, button bounds, stored factory times and 7 durable outcomes verified.')
