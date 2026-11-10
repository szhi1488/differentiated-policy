$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '../frontend')
try {
    if (-not (Test-Path 'node_modules')) {
        & npm.cmd ci --cache ../.local-cache/npm --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    }
    & npm.cmd run dev
    if ($LASTEXITCODE -ne 0) { throw '前端启动失败，请检查上方输出。' }
} finally {
    Pop-Location
}
