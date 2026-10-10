"""Compare owned UI business states and measured warning/scroll behavior; do not normalize away differences."""
from pathlib import Path
import json,sys
from decimal import Decimal
root=Path.cwd()
base=root/'frontend/test-results'/sys.argv[1]
a=json.loads(next(base.glob('factory-identity*prototype/states.json')).read_text('utf8'))
b=json.loads(next(base.glob('factory-identity*migration/states.json')).read_text('utf8'))
assert len(a)==len(b)==7
out=[]
for left,right in zip(a,b):
    assert left['label']==right['label']
    def business(state,java):
        facts=state['data']['factories']
        factories=[(r['code' if java else 'factory_code'],r['name' if java else 'factory_name'],r['source'],bool(r['enabled'])) for r in facts]
        orders=[(r['bill_no'],r['org_name'],r['factory_code'],str(Decimal(r['qty']).normalize())) for r in state['data']['orders']]
        return factories,orders
    assert business(left,False)==business(right,True),left['label']
    assert left['scroll']==right['scroll'],(left['label'],left['scroll'],right['scroll'])
    assert len(left['warning'])==len(right['warning'])
    for lw,rw in zip(left['warning'],right['warning']):
        assert lw==rw,(left['label'],'warning',lw,rw)
    out.append({'label':left['label'],'orderedFactoryBusinessRows':len(left['data']['factories']),'orders':len(left['data']['orders']),'scrollTop':left['scroll'],'warningGeometryAndPaintEqual':bool(left['warning'])})
def writes(kind):
    values=json.loads(next(base.glob('factory-identity*'+kind+'/writes.json')).read_text('utf8'))
    result=[]
    for value in values:
        x=value.get('data',value)
        result.append({k:int(x[k]) for k in ('total','inserted','updated')} | {'conflicts':x['conflicts']})
    return result
assert writes('prototype')==writes('migration')
result={'states':out,'syncResults':writes('prototype'),'screenshots':14,'scope':'owned single-bill and organization changes; full UI matrix remains incomplete'}
(root/'tools/.runtime/comparison-20261008/stage47-identity-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print('Seven paired states, ordered factories, order ownership, sync results and warning paint/geometry match.')
