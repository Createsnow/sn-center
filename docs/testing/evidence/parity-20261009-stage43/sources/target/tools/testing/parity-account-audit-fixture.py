"""Reserve UI-created accounts and clean only IDs recorded by the comparison UI suite."""

import argparse
import importlib.util
import json
from pathlib import Path
import secrets
import re
import time

import pymysql

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / 'deploy/.secrets/comparison-20261008'
spec = importlib.util.spec_from_file_location('common', Path(__file__).with_name('parity-auth-fixture.py'))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=('java', 'python'))
    parser.add_argument('action', choices=('prepare', 'clean'))
    parser.add_argument('tag')
    args = parser.parse_args()
    assert re.fullmatch(r'[0-9]{8,14}', args.tag)
    java = args.kind == 'java'
    path = PRIVATE / f'account-audit-{args.kind}-{args.tag}.json'
    with pymysql.connect(host='127.0.0.1', port=33308, user=args.kind+'_compare',
                         password=(PRIVATE / ('mysql-'+args.kind)).read_text().strip(),
                         database='sn_'+args.kind+'_compare', cursorclass=pymysql.cursors.DictCursor) as con, con.cursor() as c:
        current = common.accounts(c, java)
        if java:
            c.execute('SELECT id,credential_version FROM sys_user')
            credentials = {str(r['id']):r['credential_version'] for r in c.fetchall()}
        else:
            credentials = {}
        if args.action == 'prepare':
            assert not path.exists(), 'Existing fixture must be inspected first'
            names = [f'cmp_a43_{args.tag}A', f'cmp_a43_{args.tag}M']
            assert not any(r['username' if java else 'emp_no'] in names for r in current)
            state = {'credentials': credentials, 'before': current, 'names': names, 'created': [], 'password': 'Acc9!'+secrets.token_hex(18), 'changed': 'Next8!'+secrets.token_hex(18)}
            path.write_text(json.dumps(state, default=str), 'utf8')
            print(args.kind+': reserved unused audit-test names; no accounts seeded')
            return
        state = json.loads(path.read_text('utf8'))
        ids = set(state['created'])
        assert {k:v for k,v in credentials.items() if k not in ids} == state['credentials']
        assert common.digest([r for r in current if str(r['id']) not in ids]) == common.digest(state['before'])
        for row in current:
            if str(row['id']) not in ids:
                continue
            assert row['username' if java else 'emp_no'] in state['names']
            if java:
                c.execute('SELECT COUNT(*) n FROM sys_task WHERE user_id=%s', (row['id'],))
                assert c.fetchone()['n'] == 0
                c.execute('DELETE FROM sn_user_factory WHERE user_id=%s', (row['id'],))
                c.execute('DELETE FROM sys_user_role WHERE user_id=%s', (row['id'],))
            c.execute('DELETE FROM sys_user WHERE id=%s' if java else 'DELETE FROM sn_user WHERE id=%s', (row['id'],))
        con.commit()
        assert common.digest(common.accounts(c, java)) == common.digest(state['before'])
        path.rename(path.with_name(path.stem+f'.{time.time_ns()}.completed.json'))
        print(args.kind+': removed owned UI-created accounts; retained all real audits')


if __name__ == '__main__':
    main()
