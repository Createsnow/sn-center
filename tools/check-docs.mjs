import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const files = ['README.md', 'AGENTS.md'];
function walk(directory) {
  for (const item of readdirSync(resolve(root, directory), { withFileTypes: true })) {
    const path = `${directory}/${item.name}`;
    if (item.isDirectory()) walk(path);
    else if (path.endsWith('.md')) files.push(path);
  }
}
walk('docs');
let checked = 0;
const failures = [];
for (const path of files) {
  const content = readFileSync(resolve(root, path), 'utf8').replace(/```[\s\S]*?```/g, '');
  for (const match of content.matchAll(/\]\(([^)]+)\)/g)) {
    const target = match[1].replace(/^<|>$/g, '').split('#')[0];
    if (!target || /^[a-z][a-z0-9+.-]*:/i.test(target)) continue;
    const absolute = resolve(root, dirname(path), decodeURIComponent(target));
    checked++;
    if (!existsSync(absolute)) failures.push(`${path} -> ${relative(root, absolute)}`);
  }
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`Current documentation: ${checked} local links resolve.`);
