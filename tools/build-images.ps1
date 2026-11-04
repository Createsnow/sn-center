[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($artifact in @('backend/adtek-server/target/adtek-server-0.1.0.jar', 'frontend/dist/index.html')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $artifact))) {
        throw "Build and verify first: missing $artifact"
    }
}
foreach ($component in @('api', 'web')) {
    & docker build --file (Join-Path $projectRoot "deploy/$component/Dockerfile") --tag "adtek-platform-template-${component}:0.1.0" $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Container image build failed: $component"
    }
}
