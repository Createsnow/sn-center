from pathlib import Path
import json
root=Path.cwd(); directory=root/'frontend/test-results/stage32-audit-verified-ui'
out={'tables':[],'layouts':[],'details':[],'files':[]}
for family in ('audit-page-','audit-files-'):
 old=next(p for p in directory.iterdir() if p.is_dir() and p.name.startswith(family) and p.name.endswith('prototype'))
 new=next(p for p in directory.iterdir() if p.is_dir() and p.name.startswith(family) and p.name.endswith('migration'))
 for file in sorted(old.glob('*.json')):
  a=json.loads(file.read_text('utf8'));b=json.loads((new/file.name).read_text('utf8'))
  if 'cells' in a:
   assert a['cells']==b['cells'],file.name
   out['tables'].append({'case':file.name,'rows':len(a['cells']),'allRawCellsAndOrderEqual':True})
   assert a['layout']==b['layout'],(file.name,'layout')
   out['layouts'].append({'case':file.name,'equal':True})
   if a['details']:
    assert a['details']==b['details'],(file.name,'details')
    out['details'].append({'case':file.name,'cells':len(a['details']),'textGeometryAndStylesEqual':True})
  if 'raw' in a:
   for key in ('raw','types','sheet','filename'):
    assert a[key]==b[key],(family,file.name,key)
   out['files'].append({'case':family+file.name,'rows':len(a['raw'])-1,'columns':len(a['raw'][0]),'allCellValuesTypesOrderSheetAndFilenameEqual':True,'sourceSha256':a['sha256'],'migrationSha256':b['sha256']})
  if 'syntheticRowsRemoved' in a:
   assert a['syntheticRowsRemoved']==b['syntheticRowsRemoved']==54
   assert a['businessAndAccountSecurityUnchanged'] and b['businessAndAccountSecurityUnchanged']
assert len(out['tables'])==15 and len(out['files'])==4
(root/'tools/.runtime/comparison-20261008/stage32-comparison.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),'utf8')
print('15 raw table/layout pairs; 20 expanded detail cells; 4 actual XLSX pairs match full values/types/order/sheet/filename.')
