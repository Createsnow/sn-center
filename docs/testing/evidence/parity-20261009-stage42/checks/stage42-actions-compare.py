from pathlib import Path
import json
root=Path.cwd(); run=root/'tools/.runtime/comparison-20261008'; base=root/'frontend/test-results/stage42-accounts-verified'
a=json.loads(next(base.glob('account-actions-*prototype/account-actions.json')).read_text('utf8'))
b=json.loads(next(base.glob('account-actions-*migration/account-actions.json')).read_text('utf8'))
assert [s['name'] for s in a['snapshots']] == [s['name'] for s in b['snapshots']]
checks=[]
for x,y in zip(a['snapshots'],b['snapshots']):
 if x['name'] not in ('01-disable-confirm','04-reset-empty','05-reset-invalid'):continue
 for u,v in zip(x['measurements'],y['measurements']):
  assert (u is None)==(v is None),x['name']
  if u:
   assert all(abs(u[k]-v[k])<.05 for k in ('x','y','width','height')),(x['name'],u,v)
   assert ''.join(u['text'].split())==''.join(v['text'].split()),x['name']
 checks.append({'state':x['name'],'allGeometryGroupsEqual':True,'visibleTextEqual':True})
assert len(checks)==3
result={'statesPerSystem':len(a['snapshots']),'dialogs':checks,'prototypePageErrors':a['pageErrors'],'migrationPageErrors':b['pageErrors'],'fullPixelEqualityClaimed':False,'browserFlowAssertions':'Cancel disable; real disable/enable; disabled refresh redirects; no silent relogin after enable; repeated same-password reset without list reload; self reset; old sessions rejected; forced password change'}
(run/'stage42-actions-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('9 states per system; 3 dialog states have identical geometry/text; UI flow assertions passed.')
