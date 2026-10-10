"""Owned factory/organization scenario. Preserve every pre-existing business row and all real audits."""
import argparse
import json
from pathlib import Path
import time
import pymysql

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / 'deploy/.secrets/comparison-20261008'
RUN = ROOT / 'tools/.runtime/comparison-20261008'
BILL = 'CMP-F46-MO'
CODES = ['CMP_F46_MANUAL', 'CMP_F46_NEW', 'CMP_F46_OLD', 'CMP_F46_DUP',
         'CMP_F46_DISABLED', 'CMP_F46_NOID'] + [f'CMP_F46_LIST_{i:02}' for i in range(53)]

def norm(rows):
    return sorted(json.dumps(r, default=str, ensure_ascii=False, sort_keys=True) for r in rows)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=('java', 'python'))
    parser.add_argument('action', choices=('prepare', 'snapshot', 'clean'))
    args = parser.parse_args()
    java = args.kind == 'java'
    state_file = PRIVATE / f'factory-identity-{args.kind}.json'
    code = 'code' if java else 'factory_code'
    tables = ['sn_factory', 'sn_item', 'sn_key', 'sn_pi_counter'] + (
        ['sn_org_mapping', 'sn_order_bill', 'sn_order_entry', 'sn_source_entry_quota', 'sn_bill_generation']
        if java else ['sn_prd_mo', 'sn_bill_gen'])
    with pymysql.connect(host='127.0.0.1', port=33308, user=args.kind+'_compare',
                         password=(PRIVATE/('mysql-'+args.kind)).read_text().strip(),
                         database='sn_'+args.kind+'_compare', charset='utf8mb4',
                         cursorclass=pymysql.cursors.DictCursor) as db, db.cursor() as c:
        def snapshot():
            result = {}
            for table in tables:
                c.execute('SELECT * FROM '+table)
                result[table] = list(c.fetchall())
            return result
        if args.action == 'prepare':
            assert not state_file.exists(), 'An identity fixture is active'
            assert json.loads((RUN/'extra-orders.json').read_text()) == []
            assert json.loads((RUN/'k3-control.json').read_text()) == {}
            before = snapshot()
            assert not any(r[code] in CODES for r in before['sn_factory'])
            assert not any(r['bill_no'] == BILL for r in before['sn_order_bill' if java else 'sn_prd_mo'])
            state_file.write_text(json.dumps(before, default=str, ensure_ascii=False), 'utf8')
            for code_value in CODES[6:]:
                fields = 'code,name' if java else 'factory_code,factory_name'
                c.execute(f'INSERT INTO sn_factory({fields},source,created_at,updated_at) VALUES(%s,%s,\'MANUAL\',\'2026-10-09 10:00:00\',\'2026-10-09 10:00:00\')',
                          (code_value, '比较46列表'+code_value[-2:]))
            db.commit()
            row = {'F_ora_kh.FNumber':'CMP-F46-CUSTOMER', 'FBillNo':BILL, 'F_HX_PO':'CMP-F46-PO',
                   'F_HX_PI':'CMP-F46-PI', 'FMaterialId.FNumber':'CMP-F46-MAT', 'FMaterialId.FName':'比较46物料',
                   'FQty':'5', 'FStatus':'1', 'FSaleOrderNo':'CMP-F46-SO', 'FPrdOrgId.FName':'比较46旧名',
                   'FID':'9007199254794601', 'FTreeEntity_FEntryId':'94601', 'FPrdOrgId':'946101',
                   'FPrdOrgId.FNumber':'CMP_F46_MANUAL'}
            (RUN/'extra-orders.json').write_text(json.dumps([row], ensure_ascii=False), 'utf8')
            print(args.kind+': reserved directory and single source bill prepared')
            return
        if args.action == 'snapshot':
            if java:
                c.execute('SELECT b.bill_no,b.org_name,b.factory_code,e.source_entry_id,e.qty,e.factory_id,b.snapshot_version FROM sn_order_bill b JOIN sn_order_entry e ON e.bill_id=b.id WHERE b.bill_no=%s', (BILL,))
            else:
                c.execute('SELECT m.bill_no,m.prd_org_name org_name,f.factory_code,m.qty FROM sn_prd_mo m LEFT JOIN sn_factory f ON BINARY f.factory_name=BINARY m.prd_org_name WHERE m.bill_no=%s', (BILL,))
            records = list(c.fetchall())
            c.execute(f'SELECT * FROM sn_factory WHERE `{code}` IN ({",".join(["%s"]*len(CODES))}) ORDER BY `{code}`', CODES)
            print(json.dumps({'orders':records,'factories':list(c.fetchall())}, ensure_ascii=False, default=str))
            return
        before = json.loads(state_file.read_text('utf8'))
        current = snapshot()
        assert all(r['bill_no'] != BILL for r in current['sn_item']), 'Generated members exist; refuse cleanup'
        owned_factories = [r for r in current['sn_factory'] if r[code] in CODES]
        if java:
            c.execute('SELECT id FROM sn_order_bill WHERE bill_no=%s', (BILL,))
            ids = [r['id'] for r in c.fetchall()]
            for id_value in ids:
                c.execute('SELECT COUNT(*) n FROM sn_work WHERE bill_id=%s', (id_value,))
                assert c.fetchone()['n'] == 0, 'Business work references owned order'
                c.execute('SELECT * FROM sn_source_entry_quota WHERE bill_id=%s', (id_value,))
                assert all(r['generated_qty']==r['allocated_qty']==0 for r in c.fetchall())
                c.execute('SELECT COUNT(*) n FROM sn_bill_generation WHERE bill_id=%s', (id_value,))
                assert c.fetchone()['n']==0
                c.execute('DELETE FROM sn_source_entry_quota WHERE bill_id=%s', (id_value,))
                c.execute('DELETE FROM sn_order_entry WHERE bill_id=%s', (id_value,))
                c.execute('DELETE FROM sn_order_bill WHERE id=%s', (id_value,))
            c.execute('DELETE m FROM sn_org_mapping m JOIN sn_factory f ON f.id=m.factory_id WHERE f.code IN ('+','.join(['%s']*len(CODES))+')', CODES)
        else:
            assert not any(r['bill_no']==BILL for r in current['sn_bill_gen'])
            c.execute('DELETE FROM sn_prd_mo WHERE bill_no=%s', (BILL,))
        c.execute("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND (COLUMN_NAME LIKE '%%factory%%id' OR COLUMN_NAME LIKE '%%factory%%code')")
        for column in c.fetchall():
            table, name = column['TABLE_NAME'], column['COLUMN_NAME']
            if table in ('sn_factory','sn_org_mapping') or 'audit' in table:
                continue
            values = [r['id'] for r in owned_factories] if java and name.endswith('id') else [r[code] for r in owned_factories]
            if not values:
                continue
            c.execute(f'SELECT COUNT(*) n FROM `{table}` WHERE `{name}` IN ({",".join(["%s"]*len(values))})', values)
            assert c.fetchone()['n']==0, f'Factory reference remains in {table}.{name}; rolling back cleanup'
        c.execute(f'DELETE FROM sn_factory WHERE `{code}` IN ({",".join(["%s"]*len(CODES))})', CODES)
        after = snapshot()
        for table in tables:
            assert norm(after[table]) == norm(before[table]), (table, 'original data changed; rolling back cleanup')
        db.commit()
        (RUN/'extra-orders.json').write_text('[]\n','utf8')
        (RUN/'k3-control.json').write_text('{}\n','utf8')
        state_file.rename(state_file.with_name(state_file.stem+f'.{time.time_ns()}.completed.json'))
        print(args.kind+': owned rows removed; original business and all real audits preserved')

if __name__ == '__main__':
    main()
