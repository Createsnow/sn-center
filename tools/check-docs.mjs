import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const archive = 'docs/archive/python-migration';
const readJson = (path) => JSON.parse(readFileSync(resolve(root, path), 'utf8').replace(/^\uFEFF/, ''));
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const failures = [];
const archived = readJson(`${archive}/file-manifest.json`);
for (const entry of archived) {
  const path = resolve(root, entry.archived);
  if (!path.startsWith(resolve(root, archive) + '/') && !path.startsWith(resolve(root, archive) + '\\')) {
    failures.push(`Archive path outside scope: ${entry.archived}`);
    continue;
  }
  if (!existsSync(path) || statSync(path).size !== entry.bytes || sha(readFileSync(path)) !== entry.sha256) {
    failures.push(`Archive bytes differ: ${entry.original}`);
  }
}
const frozen = readJson(`${archive}/docs/reports/evidence/20261004-incremental/frozen-sql.json`);
for (const entry of frozen) {
  const path = resolve(root, 'backend/adtek-server/src/main/resources/db/migration', entry.name);
  if (sha(readFileSync(path)) !== entry.sha256) failures.push(`Frozen SQL differs: ${entry.name}`);
}
const files = ['README.md', 'AGENTS.md'];
function walk(directory) {
  for (const item of readdirSync(resolve(root, directory), { withFileTypes: true })) {
    const path = `${directory}/${item.name}`;
    if (item.isDirectory()) walk(path);
    else if (path.endsWith('.md')) files.push(path);
  }
}
walk('docs');
walk('memory');
let checked = 0;
let localScreenshotsUnavailable = 0;
for (const path of files) {
  // 来源基线里的原环境链接是历史内容，不改写、不自动联网。
  if (path.startsWith(`${archive}/docs/references/baselines/`)) continue;
  // 验收来源副本保留原字节；相对链接按其原仓库位置检查，不要求复制整棵文档目录。
  const sourceCopy = path.match(/^docs\/testing\/evidence\/[^/]+\/sources\/(target|prototype)\/(.+)$/);
  const documentPath = sourceCopy
    ? resolve(sourceCopy[1] === 'target' ? root : resolve(root, '../sn-center-python'), sourceCopy[2])
    : resolve(root, path);
  if (!existsSync(documentPath)) failures.push(`Missing source document: ${path}`);
  const content = readFileSync(resolve(root, path), 'utf8').replace(/```[\s\S]*?```/g, '');
  for (const match of content.matchAll(/\]\(([^)]+)\)/g)) {
    const target = match[1].replace(/^<|>$/g, '').split('#')[0];
    if (!target || /^[a-z][a-z0-9+.-]*:/i.test(target)) continue;
    const absolute = resolve(dirname(documentPath), decodeURIComponent(target));
    checked++;
    if (!existsSync(absolute)) {
      const targetPath = relative(root, absolute).replaceAll('\\', '/');
      // 用户要求截图仅留本地；冻结清单保留摘要，克隆仓库不包含该类图片。
      if (targetPath.startsWith('docs/testing/evidence/') && targetPath.endsWith('.png')) {
        localScreenshotsUnavailable++;
      } else {
        failures.push(`${path} -> ${relative(root, absolute)}`);
      }
    }
  }
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`Documentation links=${checked}; archive files=${archived.length} byte-identical; frozen SQL=${frozen.length} unchanged; local-only screenshot links unavailable=${localScreenshotsUnavailable}.`);
