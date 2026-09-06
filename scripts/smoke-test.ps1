<#
.SYNOPSIS
Smoke test for ai-proxy: builds the docker-compose stack, exercises the
control-plane API (register/login/key/codes/activation), drives the gateway
quota until it returns 429, verifies a PRO code lifts the limit, and finally
checks the Prometheus counters.

.DESCRIPTION
Requires Docker with compose v2 and curl.exe (bundled with Windows 10+).

Notes:
  - JSON bodies are written to temp files and passed to curl via --data-binary
    to avoid PowerShell 5.1 quote-mangling of native command arguments.
  - The stack is left running on success so you can inspect Grafana (:3000),
    Prometheus (:9090) and the apps.
#>

$ErrorActionPreference = 'Stop'

$Root    = Split-Path -Parent $PSScriptRoot
$Compose = Join-Path $Root 'deploy\docker-compose.yml'
$CpPort  = 8081
$GwPort  = 8080

$DailyLimit = 20
if ($env:SMOKE_DAILY_LIMIT) { $DailyLimit = [int]$env:SMOKE_DAILY_LIMIT }

$AdminEmail = 'admin@example.com'
$AdminPass  = 'admin-pass-123'
$Body       = '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}'

$script:CurrentStep = 'init'

function Step([string]$name) {
    $script:CurrentStep = $name
    Write-Host "RUN  $name"
}

function Assert([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function New-BodyFile([string]$json) {
    $file = Join-Path $env:TEMP ("body-" + [guid]::NewGuid().ToString('N') + ".json")
    Set-Content -Path $file -Value $json -NoNewline -Encoding UTF8
    return $file
}

function Invoke-Status {
    param([string]$Method, [string]$Url, [string]$Body, [string[]]$Headers, [string]$CookieFile)
    $curl = @('-s', '-o', 'NUL', '-w', '%{http_code}', '--max-time', '20', '-X', $Method)
    if ($Headers) { $curl += $Headers }
    if ($Body) {
        $file = New-BodyFile $Body
        $curl += '-H', 'Content-Type: application/json', '--data-binary', "@$file"
    }
    if ($CookieFile) { $curl += '-b', $CookieFile, '-c', $CookieFile }
    $curl += $Url
    return (& curl.exe @curl)
}

function Invoke-Json {
    param([string]$Method, [string]$Url, [string]$Body, [string]$CookieFile)
    $curl = @('-s', '--max-time', '20', '-X', $Method)
    if ($Body) {
        $file = New-BodyFile $Body
        $curl += '-H', 'Content-Type: application/json', '--data-binary', "@$file"
    }
    if ($CookieFile) { $curl += '-b', $CookieFile, '-c', $CookieFile }
    $curl += $Url
    $raw = (& curl.exe @curl) -join "`n"
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return ($raw | ConvertFrom-Json)
}

try {

    Step 'docker is available'
    docker version --format '{{.Server.Version}}' | Out-Null
    Assert ($LASTEXITCODE -eq 0) 'Docker daemon is not running'

    Step 'build & start the stack'
    docker compose -f $Compose up -d --build
    Assert ($LASTEXITCODE -eq 0) 'docker compose up failed'

    Step 'wait for gateway + control-plane health'
    $healthy = $false
    for ($i = 0; $i -lt 60; $i++) {
        $gw = Invoke-Status -Method GET -Url "http://localhost:$GwPort/actuator/health"
        $cp = Invoke-Status -Method GET -Url "http://localhost:$CpPort/actuator/health"
        if ($gw -eq '200' -and $cp -eq '200') { $healthy = $true; break }
        Start-Sleep -Seconds 2
    }
    Assert $healthy "services not healthy after 120s (gateway=$gw, control-plane=$cp)"

    Step 'register admin (idempotent)'
    $reg = Invoke-Status -Method POST -Url "http://localhost:$CpPort/api/auth/register" `
        -Body ("{`"email`":`"$AdminEmail`",`"password`":`"$AdminPass`"}")
    Assert (($reg -eq '201') -or ($reg -eq '409')) "admin register failed: $reg"

    Step 'register unique smoke user'
    $userEmail = "smoke-$([guid]::NewGuid().ToString('N').Substring(0, 8))@example.com"
    $userPass  = 'smoke-pass-123'
    $regUser   = Invoke-Status -Method POST -Url "http://localhost:$CpPort/api/auth/register" `
        -Body ("{`"email`":`"$userEmail`",`"password`":`"$userPass`"}")
    Assert ($regUser -eq '201') "user register failed: $regUser"

    Step 'login as user and create gateway key'
    $userJar = Join-Path $env:TEMP "smoke-user-$([guid]::NewGuid().ToString('N')).txt"
    $user = Invoke-Json -Method POST -Url "http://localhost:$CpPort/api/auth/login" `
        -Body ("{`"email`":`"$userEmail`",`"password`":`"$userPass`"}") -CookieFile $userJar
    Assert ($null -ne $user -and $null -ne $user.id) 'user login failed'
    $created = Invoke-Json -Method POST -Url "http://localhost:$CpPort/api/keys" -CookieFile $userJar
    Assert ($null -ne $created -and -not [string]::IsNullOrEmpty($created.plaintext)) 'create key failed'
    $gatewayKey = $created.plaintext
    $userId = [string]$user.id
    Write-Host "      key=$($gatewayKey.Substring(0, 16))..., user=$userId"

    Step 'negative checks: no key (403), bad key (401), unknown provider (404)'
    $noKey   = Invoke-Status -Method POST -Url "http://localhost:$GwPort/openai/v1/chat/completions" -Body $Body
    $badKey  = Invoke-Status -Method POST -Url "http://localhost:$GwPort/openai/v1/chat/completions" -Body $Body -Headers @('-H', 'X-Gateway-Key: wrong-key')
    $unknown = Invoke-Status -Method GET  -Url "http://localhost:$GwPort/nosuch/v1/x" -Headers @('-H', "X-Gateway-Key: $gatewayKey")
    Assert ($noKey  -eq '403') "no key expected 403, got $noKey"
    Assert ($badKey -eq '401') "bad key expected 401, got $badKey"
    Assert ($unknown -eq '404') "unknown provider expected 404, got $unknown"

    Step 'reset quota counters in redis'
    $redis = (docker ps --filter 'name=redis' --format '{{.Names}}' | Select-Object -First 1)
    Assert ($null -ne $redis) 'redis container not found'
    $lua = 'local ks = redis.call(''KEYS'', ARGV[1]) if #ks == 0 then return 0 end return redis.call(''DEL'', unpack(ks))'
    docker exec $redis redis-cli EVAL $lua 0 'quota:*' | Out-Null
    Assert ($LASTEXITCODE -eq 0) 'redis quota reset failed'

    Step "quota: expect 429 exactly on call $($DailyLimit + 1)"
    $hit429 = $false
    $attempts = 0
    for ($i = 1; $i -le ($DailyLimit + 5); $i++) {
        $attempts = $i
        $code = Invoke-Status -Method POST -Url "http://localhost:$GwPort/openai/v1/chat/completions" `
            -Body $Body -Headers @('-H', "X-Gateway-Key: $gatewayKey")
        if ($code -eq '429') { $hit429 = $true; break }
        Start-Sleep -Milliseconds 250
    }
    Assert $hit429 "never got 429 within $($DailyLimit + 5) attempts (last=$code)"
    Assert ($attempts -eq ($DailyLimit + 1)) "expected 429 on call $($DailyLimit + 1), got it on call $attempts"
    Write-Host "      got 429 on call $attempts as expected"

    Step 'admin generates PRO code, user activates it'
    $adminJar = Join-Path $env:TEMP "smoke-admin-$([guid]::NewGuid().ToString('N')).txt"
    $adminLogin = Invoke-Json -Method POST -Url "http://localhost:$CpPort/api/auth/login" `
        -Body ("{`"email`":`"$AdminEmail`",`"password`":`"$AdminPass`"}") -CookieFile $adminJar
    Assert ($null -ne $adminLogin -and $null -ne $adminLogin.id) 'admin login failed'
    $codes = Invoke-Json -Method POST -Url "http://localhost:$CpPort/api/admin/codes" `
        -Body '{"count":1,"tier":"PRO"}' -CookieFile $adminJar
    Assert ($null -ne $codes -and $codes.codes.Count -ge 1) 'generate PRO code failed'
    $proCode = $codes.codes[0]
    $activated = Invoke-Json -Method POST -Url "http://localhost:$CpPort/api/subscriptions/activate" `
        -Body ("{`"code`":`"$proCode`"}") -CookieFile $userJar
    Assert ($null -ne $activated -and $activated.tier -eq 'PRO') "activation failed, tier=$($activated.tier)"

    Step 'PRO user is unlimited: usage and gateway call'
    $usage = Invoke-Json -Method GET -Url "http://localhost:$CpPort/api/usage" -CookieFile $userJar
    Assert ($null -ne $usage -and $null -eq $usage.dailyLimit) 'PRO usage should show unlimited daily limit'

    # gateway caches user limits locally (TTL ~60s), so new limits may take up to a
    # minute to propagate; poll until a model call stops returning 429.
    $proOk = $false
    for ($i = 0; $i -lt 80; $i++) {
        $afterPro = Invoke-Status -Method POST -Url "http://localhost:$GwPort/openai/v1/chat/completions" `
            -Body $Body -Headers @('-H', "X-Gateway-Key: $gatewayKey")
        if ($afterPro -ne '429') { $proOk = $true; break }
        Start-Sleep -Seconds 1
    }
    Assert $proOk "PRO user still got 429 after waiting for limits cache refresh"

    Step 'prometheus metrics exposed'
    $gwMetrics = (& curl.exe -s "http://localhost:$GwPort/actuator/prometheus") -join "`n"
    $cpMetrics = (& curl.exe -s "http://localhost:$CpPort/actuator/prometheus") -join "`n"
    Assert ($gwMetrics -match 'gateway_quota_exceeded_total') 'gateway_quota_exceeded_total not found'
    Assert ($gwMetrics -match 'gateway_invalid_key_total') 'gateway_invalid_key_total not found'
    Assert ($cpMetrics -match 'http_server_requests_seconds_count') 'control-plane http metrics not found'

    Write-Host ""
    Write-Host "ALL SMOKE TESTS PASSED" -ForegroundColor Green

} catch {
    Write-Host ""
    Write-Host "FAIL  $($script:CurrentStep)" -ForegroundColor Red
    Write-Host "      $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Stack left running; inspect with: docker compose -f $Compose ps" -ForegroundColor Yellow
    exit 1
}
