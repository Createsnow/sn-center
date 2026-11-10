// 本地开发秘密：在 deploy/.secrets/ 生成缺失的 64 位十六进制随机值，已有文件保持不变，不输出秘密值。
// Windows 下仅授权当前用户与 SYSTEM 访问该目录；Linux 部署机使用 deploy/init-secrets.sh（同一文件名与格式）。
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const directory = resolve(root, 'deploy/.secrets');
const names = ['mysql-root-password', 'mysql-app-password', 'mysql-migration-password', 'sn-secret'];

mkdirSync(directory, { recursive: true });
for (const name of names) {
  const file = resolve(directory, name);
  if (!existsSync(file) || statSync(file).size === 0) {
    writeFileSync(file, randomBytes(32).toString('hex'), {
      encoding: 'utf8',
      mode: 0o644,
    });
    console.log(`created ${name}`);
  }
}

if (process.platform === 'win32') {
  const csv = execFileSync('whoami', ['/user', '/fo', 'csv', '/nh'], {
    encoding: 'utf8',
  }).trim();
  const sid = csv.split(',').pop()?.replaceAll('"', '');
  if (!sid?.startsWith('S-1-')) {
    throw new Error('Cannot resolve the current user SID for protecting deploy/.secrets.');
  }
  execFileSync('icacls', [
    directory,
    '/inheritance:r',
    '/grant:r',
    `*${sid}:(OI)(CI)F`,
    '*S-1-5-18:(OI)(CI)F',
    '/Q',
  ]);
} else {
  chmodSync(directory, 0o700);
  for (const name of names) {
    chmodSync(resolve(directory, name), 0o644);
  }
}
console.log('Local secrets are ready in deploy/.secrets (values not printed).');
