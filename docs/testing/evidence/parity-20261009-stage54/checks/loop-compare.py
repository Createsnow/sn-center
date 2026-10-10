"""Compare complete UI chain state and actual XLSX contents from both systems."""
from pathlib import Path
import json
from openpyxl import load_workbook
root=Path.cwd();folder=root/'frontend/test-results/stage54-business-final';out=root/'tools/.runtime/comparison-20261008/stage54-business-comparison.json'
dirs={k:next(folder.glob('*business*-'+k)) for k in ('prototype','migration')}
data={k:json.loads((p/'observations.json').read_text('utf8')) for k,p in dirs.items()}
left,right=data.values()
assert left['errors']==right['errors']==[]
assert left['sns']==right['sns'] and left['pi']==right['pi']
pairs=[]
for a,b in zip(left['facts'],right['facts']):
    assert a['stage']==b['stage']
    if a['stage']=='audit':continue
    assert a['observed']==b['observed'],a['stage']
    pairs.append({'stage':a['stage'],'members':a['observed']})
assert len(pairs)==4
files=[]
for i,name in enumerate(('06-first-print.xlsx','09-second-print.xlsx')):
    rows={}
    for kind,path in dirs.items():
        book=load_workbook(path/name,read_only=True,data_only=True)
        values=list(book.active.values);book.close()
        assert len(values)==4 and len(values[0])==11
        assert [v[1] for v in values[1:]]==left['sns']
        assert all(v[7]==('CMP-A' if i==0 else 'CMP-B') and v[8]=='PRINTED' for v in values[1:])
        rows[kind]=values
    assert rows['prototype'][0]==rows['migration'][0]
    # 两边各自生成领取批次编号；其余10个业务列按实际下载逐行比较。
    assert [[v for j,v in enumerate(r) if j!=9] for r in rows['prototype'][1:]]==[[v for j,v in enumerate(r) if j!=9] for r in rows['migration'][1:]]
    files.append({'name':name,'dataRows':3,'columns':11,'matchingBusinessColumns':10,'distinctBatchNumbersExpected':True})
actions={'sn:generation:execute':'SN_GENERATE','sn:allocation:execute':'SN_ALLOCATE','sn:acquire:take':'SN_ACQUIRE','sn:print:execute':'SN_PRINT','sn:transfer:direct':'TRANSFER_DIRECT'}
required={'SN_GENERATE':1,'SN_ALLOCATE':1,'SN_ACQUIRE':2,'SN_PRINT':2,'TRANSFER_DIRECT':1}
audits={}
for kind,d in data.items():
    rows=d['facts'][-1]['audits'];counts={}
    for r in rows:
        a=actions.get(r['action'],r['action'])
        if a in required:counts[a]=counts.get(a,0)+1
    assert counts==required,(kind,counts)
    audits[kind]=counts
result={'scope':'Administrator, three newly generated SN, all business writes through UI, dedicated local a48 databases and synthetic K3. No API generation or seeded SN. No claim of complete audit detail equality.',
        'statePairs':pairs,'filePairs':files,'auditActions':audits,'uiCheckpointsPerSystem':11}
out.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'statePairs':len(pairs),'actualXlsxFiles':4,'auditFactsPerSystem':7,'passed':True}))
