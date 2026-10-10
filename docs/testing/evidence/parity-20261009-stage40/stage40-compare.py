from pathlib import Path
import json
root=Path.cwd();run=root/'tools/.runtime/comparison-20261008'
directory=root/'frontend/test-results/stage40-editor8'
a=next(directory.glob('*prototype'));b=next(directory.glob('*migration'))
checks=[]
for path in sorted(a.glob('*.json')):
 if path.name=='writes.json':continue
 x=json.loads(path.read_text('utf8'));y=json.loads((b/path.name).read_text('utf8'))
 assert x['geometry']==y['geometry'],path.name
 assert x['drawnLabels']==y['drawnLabels'],path.name
 raw=x['text']==y['text']
 if not raw:
  assert path.stem=='02-edit-factory_operator'
  assert y['text']==x['text'].replace('比较测试甲厂（CMP-A）','比较测试甲厂（CMP-A）\n比较测试甲厂（CMP-A）')
  assert 'aria-live="polite"' in y['factoryMarkup'] and 'width: 0px; height: 0px;' in y['factoryMarkup'] and 'opacity: 0;' in y['factoryMarkup']
 checks.append({'state':path.stem,'allEightGeometryGroupsEqual':True,'allLabelGlyphRectanglesEqual':True,'rawTextEqual':raw,'extraInvisibleLiveAnnouncement':not raw})
assert len(checks)==13
x=json.loads((a/'writes.json').read_text('utf8'));y=json.loads((b/'writes.json').read_text('utf8'))
assert [r['status'] for r in x]==[r['status'] for r in y]==[400,400,400,400,200,409,200,200,200,200,200]
result={'states':checks,'submittedActionsPerSystem':len(x),'responseStatusSequenceEqual':True,'fullPixelEqualityClaimed':False}
(run/'stage40-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
print('13 states: 8 geometry groups and label glyphs equal; 11 real HTTP writes per system verified; one invisible aria-live duplicate explicitly retained.')
