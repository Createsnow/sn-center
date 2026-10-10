from pathlib import Path
from datetime import datetime, timezone, timedelta
import json
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008';base=root/'frontend/test-results/stage41-accounts-final'
a=next(base.glob('users-directory-*prototype'));b=next(base.glob('users-directory-*migration'))
result={'states': [],'crossSystemExcludedColumns':['last login timestamp','creation timestamp and creator'], 'metadataCheckedAgainstEachRealApi': True, 'fullPixelEqualityClaimed':False}
def business(rows): return [[c for i,c in enumerate(row) if i not in (5,6)] for row in rows if len(row)==8]
def strip_class(rows): return [{k:v for k,v in row.items() if k!='className'} for row in rows]
def formatted(value,java):
 if not value:return ''
 return datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S') if java else value
for p in sorted(a.glob('*.json')):
 if p.name=='list-responses.json':continue
 x=json.loads(p.read_text());y=json.loads((b/p.name).read_text())
 item={'state':p.stem,'businessColumnsEqual':business(x['rows'])==business(y['rows']),'headingsEqual':x['headings']==y['headings'],'geometryEqual':x['geometry']==y['geometry'], 'tagsEqual':strip_class(x['styles']['firstTags'])==strip_class(y['styles']['firstTags']), 'buttonsEqual':strip_class(x['styles']['buttons'])==strip_class(y['styles']['buttons'])}
 assert item['businessColumnsEqual'] and item['headingsEqual'] and item['geometryEqual'],item
 for folder,snapshot,java in [(a,x,False),(b,y,True)]:
  responses=json.loads((folder/'list-responses.json').read_text())
  assert all(response['status']==200 for response in responses)
  rows=[row for row in snapshot['rows'] if len(row)==8]
  candidates=[response['data']['data'] if java else response['data'] for response in responses]
  match=next(page['items'] for page in candidates if [r['username' if java else 'emp_no'] for r in page['items']]==[r[0] for r in rows])
  for row,record in zip(rows,match,strict=True):
   assert row[5]==formatted(record['lastLoginAt' if java else 'last_login_at'],java),(p.name,row[0],'login')
   assert row[6]==(record['createdByName' if java else 'created_by'] or '')+' · '+formatted(record['createdAt' if java else 'created_at'],java),(p.name,row[0],'created')
 result['states'].append(item)
(run/'stage41-directory-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf8')
print(json.dumps({'states':len(result['states']),'allGeometryEqual':all(r['geometryEqual'] for r in result['states']),'allTagsEqual':all(r['tagsEqual'] for r in result['states']),'allButtonsEqual':all(r['buttonsEqual'] for r in result['states'])}))
