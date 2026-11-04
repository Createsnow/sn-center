[CmdletBinding()]
param()
# RuntimeIT 产生实际装配契约；语义比较忽略文档对象键顺序与随机测试服务器地址。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$generated = Join-Path $projectRoot 'backend/adtek-server/target/current-openapi.json'
$published = Join-Path $projectRoot 'frontend/openapi/adtek-template.json'
if (-not (Test-Path -LiteralPath $generated)) {
    throw 'Run tools/verify.ps1 -Integration first.'
}
$check = @'
const fs = require('node:fs');
function normalized(value) {
  if (Array.isArray(value)) return value.map(normalized);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, normalized(value[k])]));
  return value;
}
const documents = process.argv.slice(1).map(p => { const d=JSON.parse(fs.readFileSync(p,'utf8')); delete d.servers; return JSON.stringify(normalized(d)); });
if (documents[0] !== documents[1]) { console.error('OpenAPI differs from the running template contract. Regenerate and review.'); process.exit(1); }
console.log('Runtime OpenAPI matches the published contract.');
'@
& node -e $check $generated $published
if ($LASTEXITCODE -ne 0) {
    throw 'OpenAPI runtime contract drift.'
}
& (Join-Path $PSScriptRoot 'pnpm.ps1') 'api:check'
exit $LASTEXITCODE
