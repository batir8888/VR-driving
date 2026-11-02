# Сборка и все проверки: серверные и клиентские алгоритмы, затем сквозная демонстрация клиент -> HTTP -> сервер.
# Запуск из каталога code:  powershell -ExecutionPolicy Bypass -File .\run_all.ps1
# Результаты (журналы) сохраняются в out\logs, хранилище демонстрации — в out\store.

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location -Path $PSScriptRoot

$logs = Join-Path $PSScriptRoot 'out\logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$port = 8090
$server = "http://127.0.0.1:$port"

function Step($title) { Write-Host ""; Write-Host "##### $title" }

function Run-Logged($log, [scriptblock]$cmd) {
    & $cmd 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath (Join-Path $logs $log) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Шаг завершился с кодом $LASTEXITCODE (см. out\logs\$log)" }
}

function Start-Server {
    $p = Start-Process -FilePath 'java' -ArgumentList @('-Dstdout.encoding=UTF-8', '-cp', 'out/server', 'sim.HttpApi',
        '--port', "$port", '--data', 'data', '--store', 'out/store') -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'server-stdout.txt') -RedirectStandardError (Join-Path $logs 'server-stderr.txt')
    for ($i = 0; $i -lt 50; $i++) {
        try { Invoke-WebRequest -UseBasicParsing -Uri "$server/attempts" -TimeoutSec 1 | Out-Null; return $p } catch { Start-Sleep -Milliseconds 200 }
    }
    throw 'Сервер не запустился'
}

Step '1. Сборка серверной части (javac)'
New-Item -ItemType Directory -Force -Path 'out\server' | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java -Path 'server-java\src' | ForEach-Object { $_.FullName }
Run-Logged 'build-java.txt' { javac -encoding UTF-8 -Xlint:-unchecked -d out/server @sources }

Step '2. Серверные проверки A1, A4, A5, A6, A7 (без VR и сети)'
Run-Logged 'server-checks.txt' { java '-Dstdout.encoding=UTF-8' -cp out/server checks.ServerChecks }

Step '3. Сборка клиентской части (dotnet)'
Run-Logged 'build-csharp.txt' { dotnet build client-csharp/Simulator.Client.Demo/Simulator.Client.Demo.csproj -nologo -v q }
Run-Logged 'build-csharp-checks.txt' { dotnet build client-csharp/Simulator.Client.Checks/Simulator.Client.Checks.csproj -nologo -v q }

Step '4. Клиентские проверки A1 (клиент), A2, A3 (без Unity и сети)'
Run-Logged 'client-checks.txt' { dotnet run --no-build --project client-csharp/Simulator.Client.Checks -- data }

Step '5. Входные данные клиента: запись траекторий'
Run-Logged 'generate.txt' { dotnet run --no-build --project client-csharp/Simulator.Client.Demo -- generate data samples/trajectories }

Step '6. Сквозная демонстрация: клиент (A1-A3) -> HTTP -> сервер (A4-A6)'
if (Test-Path 'out\store') { Remove-Item -Recurse -Force 'out\store' }
$srv = Start-Server
try {
    Write-Host '--- 6a. Нормальный канал ---'
    Run-Logged 'replay-normal.txt' { dotnet run --no-build --project client-csharp/Simulator.Client.Demo -- replay samples/trajectories $server }
    Write-Host '--- 6b. Канал с обрывами, ответом 503 и потерянным подтверждением ---'
    Run-Logged 'replay-flaky.txt' { dotnet run --no-build --project client-csharp/Simulator.Client.Demo -- replay samples/trajectories $server flaky }
    Write-Host '--- 6c. Сравнение попыток (A7): попытка a-0015 с предыдущей попыткой обучающегося по тому же упражнению ---'
    Run-Logged 'compare.txt' { dotnet run --no-build --project client-csharp/Simulator.Client.Demo -- compare $server a-0015 }
} finally {
    Stop-Process -Id $srv.Id -Force
}

Step '7. Перезапуск сервера: история должна открываться'
$srv = Start-Server
try {
    Run-Logged 'history-after-restart.txt' { dotnet run --no-build --project client-csharp/Simulator.Client.Demo -- history $server }
    $res = Invoke-WebRequest -UseBasicParsing -Uri "$server/attempts/a-0001/result"
    Write-Host ("Результат a-0001 после перезапуска: HTTP " + $res.StatusCode)
} finally {
    Stop-Process -Id $srv.Id -Force
}

Step '8. Компиляция клиентских скриптов в Unity (если редактор установлен)'
# версия редактора берётся из проекта: другая версия может изменить манифест пакетов
$verLine = Get-Content (Join-Path $PSScriptRoot 'unity-client\ProjectSettings\ProjectVersion.txt') | Select-Object -First 1
$ver = ($verLine -split ':')[1].Trim()
$unity = Join-Path 'C:\Program Files\Unity\Hub\Editor' "$ver\Editor\Unity.exe"
if (-not (Test-Path $unity)) { $unity = $null }
if ($unity) {
    $ulog = Join-Path $logs 'unity-compile.txt'
    & $unity -batchmode -nographics -quit -projectPath (Join-Path $PSScriptRoot 'unity-client') -logFile $ulog | Out-Null
    $errors = Select-String -Path $ulog -Pattern 'error CS' -SimpleMatch -ErrorAction SilentlyContinue
    $built = Select-String -Path $ulog -Pattern 'Assembly-CSharp.dll' -SimpleMatch -ErrorAction SilentlyContinue
    if ($errors -or -not $built) { throw "Ошибки компиляции в Unity (см. $ulog)" }
    Write-Host "Unity ($unity): скрипты скомпилированы без ошибок"
} else {
    Write-Host 'Редактор Unity не найден: шаг пропущен'
}

Write-Host ""
Write-Host "ВСЕ ШАГИ ВЫПОЛНЕНЫ. Журналы: out\logs"
