$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'mvnw.cmd') --quiet -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. See Maven output.' }
    $databasePath = Join-Path $PSScriptRoot 'data\tasks.db'
    & java --enable-native-access=ALL-UNNAMED "-Dtask.db=$databasePath" -jar (Join-Path $PSScriptRoot 'target\task-console-1.0.0.jar')
    if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
} finally {
    Pop-Location
}
