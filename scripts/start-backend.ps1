$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '../backend')
try {
    & mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw '后端启动失败，请检查上方 Maven 输出。' }
} finally {
    Pop-Location
}
