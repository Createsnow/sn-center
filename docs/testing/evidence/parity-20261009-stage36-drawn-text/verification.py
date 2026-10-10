"""Append actual language text verification without altering the stage36 archive."""
from pathlib import Path
import hashlib, json, shutil

root = Path.cwd()
run = root / 'tools/.runtime/comparison-20261008'
previous = root / 'docs/testing/evidence/parity-20261009-stage36'
dest = root / 'docs/testing/evidence/parity-20261009-stage36-drawn-text'
assert not dest.exists()
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
manifest = json.loads((previous / 'manifest.json').read_text('utf8'))
for name, digest in manifest.items():
    assert sha(previous / name) == digest, name
old = json.loads((previous / 'summary.json').read_text('utf8'))['sourceHashes']
changed = {name for name, digest in old.items() if sha(root / name) != digest}
assert changed == {'frontend/src/styles.css', 'frontend/e2e/parity/login-layout.spec.ts'}, changed
directory = root / 'frontend/test-results/stage36-drawn-layout'
a = next(directory.glob('*-prototype'))
b = next(directory.glob('*-migration'))
comparisons = []
for file in sorted(a.glob('*.json')):
    source = json.loads(file.read_text('utf8'))
    target = json.loads((b / file.name).read_text('utf8'))
    assert source == target and len(source) == 15, file.name
    comparisons.append({'case': file.name, 'all15GroupsEqual': True, 'languageValueMeasuresVisibleTextOwner': True})
assert len(comparisons) == 18
ui = json.loads((run / 'stage36-drawn-layout-results.json').read_text('utf8'))
assert ui['stats']['expected'] == 2 and all(ui['stats'][key] == 0 for key in ('unexpected', 'skipped', 'flaky'))
shutil.copytree(directory, dest / 'ui')
for name in ('stage36-drawn-layout-results.json', 'stage36-drawn-layout.log', 'stage36-drawn-language2.log', 'stage36-format7.log', 'stage36-format-check7.log', 'stage36-lint-drawn.log', 'stage36-build-drawn.log', 'stage36-final-state.json'):
    shutil.copyfile(run / name, dest / name)
shutil.copyfile(Path(__file__), dest / 'verification.py')
summary = {
    'stage': '36-drawn-text', 'goalComplete': False, 'replacesPriorArchive': False,
    'ui': ui['stats'], 'workers': 1, 'retries': 0,
    'screenshots': len(list((dest / 'ui').rglob('*.png'))),
    'changedSourcesSinceMainArchive': sorted(changed),
    'reason': 'The selected language is painted by the content text node, not the internal empty input. Corrected its color and measured the actual visible text owner.',
    'comparisons': comparisons, 'fullPixelEqualityClaimed': False,
    'sourceHashes': {name: sha(root / name) for name in sorted(old)},
    'previousManifestFilesPreserved': len(manifest),
}
(dest / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), 'utf8')
manifest = {p.relative_to(dest).as_posix(): sha(p) for p in sorted(dest.rglob('*')) if p.is_file()}
(dest / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), 'utf8')
print(json.dumps({'files': len(manifest), 'sources': len(old), 'screenshots': summary['screenshots'], 'goalComplete': False}))
