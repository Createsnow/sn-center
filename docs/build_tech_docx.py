"""把 docs/系统技术方案.md 转成 docs/系统技术方案.docx。

1. 用 mermaid-cli 把文中每个 mermaid 图渲染成 PNG，存到 docs/images/系统技术方案/；
2. 用 pandoc 转 docx（中文字体模板、自动目录），再给表格加边框、图题居中。

依赖：Node.js（npx @mermaid-js/mermaid-cli）、pip install pypandoc_binary python-docx pillow
用法（仓库根目录）：python docs/build_tech_docx.py [--chrome /path/to/chrome]
"""
import argparse, json, os, re, subprocess, tempfile
from PIL import Image
import pypandoc
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
from docx.enum.text import WD_ALIGN_PARAGRAPH

ap = argparse.ArgumentParser()
ap.add_argument('--chrome', help='Chromium 可执行文件路径（mermaid-cli 找不到浏览器时指定）')
args = ap.parse_args()
SP = tempfile.mkdtemp()
SRC = 'docs/系统技术方案.md'
OUT = 'docs/系统技术方案.docx'
IMG = 'docs/images/系统技术方案'
names = ['fig2-1-function','fig2-2-tech','fig2-3-data-layer','fig2-4-sn-state','fig2-5-er-base',
         'fig2-6-er-item','fig2-7-context','fig2-8-deploy','fig3-1-generate-seq']

s = open(SRC, encoding='utf-8').read()
blocks = re.findall(r'```mermaid\n(.*?)\n```', s, flags=re.S)
assert len(blocks) == len(names), f'文中有 {len(blocks)} 个 mermaid 图，names 列了 {len(names)} 个'
os.makedirs(IMG, exist_ok=True)
pcfg = {'args': ['--no-sandbox']}
if args.chrome: pcfg['executablePath'] = args.chrome
json.dump(pcfg, open(f'{SP}/p.json', 'w'))
for b, n in zip(blocks, names):
    open(f'{SP}/{n}.mmd', 'w', encoding='utf-8').write(b)
    subprocess.run(['npx', '-y', '-p', '@mermaid-js/mermaid-cli', 'mmdc', '-i', f'{SP}/{n}.mmd',
                    '-o', f'{IMG}/{n}.png', '-p', f'{SP}/p.json', '-s', '3', '-b', 'white'], check=True)
# 手写目录换成 Word 自动目录
s = re.sub(r'## 目录\n.*?(?=\n## 第一章)', '', s, flags=re.S)
title = s.split('\n', 1)[0].lstrip('# ').strip()
s = s.split('\n', 1)[1]

it = iter(names)
MAXW, MAXH = 16.0, 21.0
def img(_m):
    n = next(it)
    w, h = Image.open(f'{IMG}/{n}.png').size
    cw = MAXW; ch = cw * h / w
    if ch > MAXH: ch = MAXH; cw = ch * w / h
    return f'![]({IMG}/{n}.png){{width={cw:.1f}cm}}'
s = re.sub(r'```mermaid\n.*?\n```', img, s, flags=re.S)
# 图题居中：**图 x-y …** 独占一行 → 用自定义样式
s = re.sub(r'^\*\*(图 \d-\d[^*]*)\*\*$', r'::: {custom-style="Figure Caption"}\n\1\n:::', s, flags=re.M)
md = f'---\ntitle: "{title}"\n---\n' + s
open(f'{SP}/doc.md', 'w', encoding='utf-8').write(md)

# 参考模板：中文字体
ref = f'{SP}/ref.docx'
subprocess.run([pypandoc.get_pandoc_path(), '-o', ref, '--print-default-data-file', 'reference.docx'], check=True)
d = Document(ref)
def font(style, east, west='Arial', size=None, bold=None, color=None):
    st = d.styles[style]
    st.font.name = west
    rpr = st.element.get_or_add_rPr()
    rf = rpr.find(qn('w:rFonts'))
    if rf is None:
        rf = OxmlElement('w:rFonts'); rpr.append(rf)
    for k in ('w:ascii', 'w:hAnsi', 'w:cs'): rf.set(qn(k), west)
    rf.set(qn('w:eastAsia'), east)
    for k in ('w:asciiTheme', 'w:hAnsiTheme', 'w:eastAsiaTheme', 'w:cstheme'):
        if rf.get(qn(k)) is not None: del rf.attrib[qn(k)]
    if size: st.font.size = Pt(size)
    if bold is not None: st.font.bold = bold
    if color is not None: st.font.color.rgb = RGBColor.from_string(color)
for st in ('Normal', 'Body Text', 'First Paragraph', 'Compact'):
    font(st, '宋体', 'Calibri', 10.5)
font('Title', '黑体', 'Arial', 20, True, '1F3864')
for i, sz in ((1, 16), (2, 14), (3, 12), (4, 11)):
    font(f'Heading {i}', '黑体', 'Arial', sz, True, '1F3864')

font('Verbatim Char', '等线', 'Consolas', 9)
d.save(ref)

pypandoc.convert_file(f'{SP}/doc.md', 'docx', outputfile=OUT,
                      extra_args=[f'--reference-doc={ref}', '--toc', '--toc-depth=2', '--shift-heading-level-by=-1',
                                  '-M', 'toc-title=目录', '--resource-path=.'])

# 后处理：表格加边框、表头底色，图题居中
d = Document(OUT)
sec = d.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.left_margin = sec.right_margin = Cm(2.2)
sec.top_margin = sec.bottom_margin = Cm(2.2)
sec.header_distance = sec.footer_distance = Cm(1.2)
sec.gutter = Cm(0)
for t in d.tables:
    tblPr = t._tbl.tblPr
    for tag in ('w:tblW', 'w:tblBorders'):
        for old in tblPr.findall(qn(tag)): tblPr.remove(old)
    w = OxmlElement('w:tblW'); w.set(qn('w:w'), '5000'); w.set(qn('w:type'), 'pct')
    b = OxmlElement('w:tblBorders')
    for e in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'):
        x = OxmlElement(f'w:{e}'); x.set(qn('w:val'), 'single'); x.set(qn('w:sz'), '4'); x.set(qn('w:space'), '0'); x.set(qn('w:color'), '999999')
        b.append(x)
    after = [qn(f'w:{n}') for n in ('shd', 'tblLayout', 'tblCellMar', 'tblLook', 'tblCaption', 'tblDescription')]
    anchor = next((c for c in tblPr if c.tag in after), None)
    for el in (w, b):
        if anchor is None: tblPr.append(el)
        else: anchor.addprevious(el)
    # tblW 须在 jc / tblInd 之前
    for n in ('jc', 'tblCellSpacing', 'tblInd'):
        for el in tblPr.findall(qn(f'w:{n}')): b.addprevious(el)
    for c in t.rows[0].cells:
        shd = OxmlElement('w:shd'); shd.set(qn('w:val'), 'clear'); shd.set(qn('w:color'), 'auto'); shd.set(qn('w:fill'), 'D9E2F3')
        tcPr = c._tc.get_or_add_tcPr()
        later = [qn(f'w:{n}') for n in ('noWrap', 'tcMar', 'textDirection', 'tcFitText', 'vAlign', 'hideMark')]
        anchor = next((x for x in tcPr if x.tag in later), None)
        if anchor is None: tcPr.append(shd)
        else: anchor.addprevious(shd)
        for p in c.paragraphs:
            for r in p.runs: r.bold = True
    for row in t.rows:
        for c in row.cells:
            for p in c.paragraphs:
                p.paragraph_format.space_before = Pt(1); p.paragraph_format.space_after = Pt(1)
                for r in p.runs: r.font.size = Pt(9)
for p in d.paragraphs:
    if p.style.name == 'Figure Caption':
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        for r in p.runs: r.bold = True; r.font.size = Pt(10)
    if any(x.tag == qn('w:drawing') for r in p.runs for x in r._r):
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
st = d.settings.element
if st.find(qn('w:updateFields')) is None:
    u = OxmlElement('w:updateFields'); u.set(qn('w:val'), 'true')
    order = ['w:hdrShapeDefaults','w:footnotePr','w:endnotePr','w:compat','w:docVars','w:rsids','m:mathPr','w:attachedSchema','w:themeFontLang','w:clrSchemeMapping','w:doNotIncludeSubdocsInStats','w:doNotAutoCompressPictures','w:forceUpgrade','w:captions','w:readModeInkLockDown','w:smartTagType','sl:schemaLibrary','w:shapeDefaults','w:doNotEmbedSmartTags','w:decimalSymbol','w:listSeparator']
    anchor = next((c for c in st if any(c.tag == qn(o) for o in order if not o.startswith(('m:','sl:')))), None)
    if anchor is None: st.append(u)
    else: anchor.addprevious(u)
d.save(OUT)
print('ok')
