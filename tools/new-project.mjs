// 从模板复制新项目：只复制 Git 跟踪文件，替换项目名、数据库名与端口，记录来源版本。
// 不复制秘密、账号、缓存、数据或 Git 历史。用法见 tools/new-project.bat 或 --help。
import { execFileSync } from 'node:child_process';
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const usage =
  'Usage: node tools/new-project.mjs --destination <dir> --name <project-name> ' +
  '[--mysql-port 33326] [--redis-port 36399] [--api-port 18100] [--web-port 15193] [--deploy-port 18180]';
const { values } = parseArgs({
  options: {
    destination: { type: 'string' },
    name: { type: 'string' },
    'mysql-port': { type: 'string', default: '33326' },
    'redis-port': { type: 'string', default: '36399' },
    'api-port': { type: 'string', default: '18100' },
    'web-port': { type: 'string', default: '15193' },
    'deploy-port': { type: 'string', default: '18180' },
    help: { type: 'boolean', default: false },
  },
});
function fail(message) {
  console.error(`[ERROR] ${message}\n${usage}`);
  process.exit(1);
}
if (values.help) {
  console.log(usage);
  process.exit(0);
}
if (!values.destination || !values.name) fail('--destination and --name are required.');
const name = values.name;
if (!/^[a-z][a-z0-9-]{2,40}$/.test(name)) fail('Project name must match ^[a-z][a-z0-9-]{2,40}$.');
if (name === 'adtek-platform-template') fail('Choose a distinct project name.');
const portKeys = ['mysql-port', 'redis-port', 'api-port', 'web-port', 'deploy-port'];
const ports = Object.fromEntries(
  portKeys.map((key) => {
    const port = Number(values[key]);
    if (!Number.isInteger(port) || port < 1024 || port > 65535) fail(`Invalid ${key}.`);
    return [key, String(port)];
  }),
);
if (new Set(Object.values(ports)).size !== portKeys.length) fail('All ports must be distinct.');

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const targetRoot = resolve(values.destination);
if (existsSync(targetRoot)) fail('Destination must not exist.');
if (targetRoot === sourceRoot || targetRoot.startsWith(sourceRoot + sep)) {
  fail('Destination must be outside the template checkout.');
}
const git = (...args) => execFileSync('git', ['-C', sourceRoot, ...args], { encoding: 'utf8' });
if (git('status', '--porcelain').trim()) fail('Commit or restore template changes before copying.');
const commit = git('rev-parse', 'HEAD').trim();
const files = git('-c', 'core.quotepath=false', 'ls-files', '-z').split('\0').filter(Boolean);

// 一次匹配原始文本，避免替换结果再次被替换。
const replacements = new Map([
  ['adtek-platform-template', name],
  ['adtek_template', name.replaceAll('-', '_')],
  ['33316', ports['mysql-port']],
  ['36389', ports['redis-port']],
  ['18090', ports['api-port']],
  ['15183', ports['web-port']],
  ['18080', ports['deploy-port']],
]);
// 镜像摘要（sha256:...）整体匹配并原样保留，避免其中偶然出现的端口数字被替换。
const digest = /^sha256:[0-9a-f]{64}$/;
const pattern = new RegExp(['sha256:[0-9a-f]{64}', ...replacements.keys()].join('|'), 'g');
const textExtensions = new Set([
  '.java',
  '.xml',
  '.yml',
  '.yaml',
  '.json',
  '.sh',
  '.ts',
  '.tsx',
  '.html',
  '.conf',
  '.bat',
  '.mjs',
  '.md',
  '.sql',
  '.example',
]);
const keepBytes = new Set(['new-project.mjs', 'pnpm-lock.yaml', 'images.lock.json']);

mkdirSync(targetRoot, { recursive: true });
for (const relative of files) {
  if (/(^|\/)(\.secrets|\.tools|node_modules|target|test-results|dist)(\/|$)/.test(relative)) {
    fail(`Unexpected tracked runtime path: ${relative}`);
  }
  const source = resolve(sourceRoot, relative);
  const target = resolve(targetRoot, relative);
  if (!target.startsWith(targetRoot + sep)) fail('Tracked path leaves destination.');
  mkdirSync(dirname(target), { recursive: true });
  const fileName = basename(relative);
  // 全部跟踪的文本文件（含 README、docs、.gitlab-ci.yml）都替换；锁文件与本脚本保持原字节。
  const rewrite =
    !keepBytes.has(fileName) &&
    (fileName === 'Dockerfile' || textExtensions.has(extname(relative)));
  if (rewrite) {
    const content = readFileSync(source, 'utf8');
    writeFileSync(
      target,
      content.replace(pattern, (match) =>
        digest.test(match) ? match : (replacements.get(match) ?? match),
      ),
      'utf8',
    );
    // 保留可执行位（如 deploy/init-secrets.sh）。
    chmodSync(target, statSync(source).mode);
  } else {
    copyFileSync(source, target);
  }
}
const origin = {
  template: 'adtek-platform-template',
  version: '0.1.0',
  commit,
  project: name,
  createdAtUtc: new Date().toISOString(),
  ports: Object.fromEntries(portKeys.map((key) => [key, Number(ports[key])])),
};
writeFileSync(resolve(targetRoot, 'template-origin.json'), `${JSON.stringify(origin, null, 2)}\n`);
console.log(
  `Created ${name} from template 0.1.0 at ${targetRoot}. Run tools\\setup.bat in the new project.`,
);
