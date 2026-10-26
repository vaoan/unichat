# Generates Synapse + mautrix bridge configs and registrations for Unichat, then starts the stack (Windows).
# Usage: .\setup.ps1 [-Domain localhost] [-AdminUser admin] [-Bridges whatsapp,telegram,...]
# Env:   TELEGRAM_API_ID / TELEGRAM_API_HASH (from https://my.telegram.org).
# Safe to re-run: existing files are kept and only the Unichat-relevant keys are (re)applied.
param(
    [string]$Domain = "",
    [string]$AdminUser = "admin",
    [string[]]$Bridges = @("whatsapp", "telegram", "signal", "instagram", "facebook", "discord", "slack", "gmessages", "twitter")
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$YqImage = "mikefarah/yq:4"
$SynapseImage = "matrixdotorg/synapse:latest"
$Ports = @{ whatsapp = 29318; telegram = 29317; signal = 29328; instagram = 29319; facebook = 29320;
            discord = 29334; slack = 29335; gmessages = 29336; twitter = 29327; linkedin = 29341 }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker no está instalado." }

function New-Secret([int]$Length = 32) {
    $chars = [char[]]"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    $bytes = New-Object byte[] $Length
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

$Data = Join-Path $PSScriptRoot "data"
New-Item -ItemType Directory -Force -Path $Data | Out-Null

if (-not (Test-Path ".env")) {
    if (-not $Domain) {
        $Domain = Read-Host "Nombre del servidor Matrix (dominio) [localhost]"
        if (-not $Domain) { $Domain = "localhost" }
    }
    @("DOMAIN=$Domain", "POSTGRES_PASSWORD=$(New-Secret 32)", "DOUBLE_PUPPET_TOKEN=$(New-Secret 48)") |
        Set-Content -Encoding ascii ".env"
    Write-Host "Creado server\.env (contiene contraseñas; no lo compartas)."
}
$EnvVars = @{}
Get-Content ".env" | Where-Object { $_ -match "^\s*([^#=]+)=(.*)$" } | ForEach-Object { $EnvVars[$Matches[1].Trim()] = $Matches[2] }
$Domain = $EnvVars["DOMAIN"]
$PgPass = $EnvVars["POSTGRES_PASSWORD"]
$DpToken = $EnvVars["DOUBLE_PUPPET_TOKEN"]
$env:POSTGRES_PASSWORD = $PgPass
$AdminId = "@${AdminUser}:$Domain"

# Runs yq in Docker (as root) on a file under .\data with the given variables exported.
function Invoke-YqEdit([string]$File, [string]$Expr, [hashtable]$Vars) {
    $envArgs = @()
    foreach ($k in $Vars.Keys) { $envArgs += @("-e", "$k=$($Vars[$k])") }
    # Expressions go through a file: Windows PowerShell mangles embedded quotes in native arguments.
    Set-Content -Encoding ascii -NoNewline -Path (Join-Path $Data ".yq-expr") -Value $Expr
    & docker run --rm --user root -v "${Data}:/data" @envArgs $YqImage -i --from-file /data/.yq-expr "/data/$File"
    if ($LASTEXITCODE -ne 0) { throw "yq falló en $File" }
}
function Get-Yq([string]$File, [string]$Expr) {
    Set-Content -Encoding ascii -NoNewline -Path (Join-Path $Data ".yq-expr") -Value $Expr
    (& docker run --rm --user root -v "${Data}:/data" $YqImage --from-file /data/.yq-expr "/data/$File" | Out-String).Trim()
}

# --- Synapse -------------------------------------------------------------------------------
if (-not (Test-Path "data\synapse\homeserver.yaml")) {
    Write-Host "Generando configuración de Synapse para $Domain..."
    New-Item -ItemType Directory -Force -Path "data\synapse" | Out-Null
    & docker run --rm -v "${Data}\synapse:/data" -e "SYNAPSE_SERVER_NAME=$Domain" -e "SYNAPSE_REPORT_STATS=no" $SynapseImage generate
    if ($LASTEXITCODE -ne 0) { throw "No se pudo generar la configuración de Synapse" }
}

# --- Double puppeting ----------------------------------------------------------------------
$DomainRe = $Domain.Replace(".", "\.")
@"
id: doublepuppet
url:
as_token: "$DpToken"
hs_token: "$(New-Secret 48)"
sender_localpart: "doublepuppet_$(New-Secret 8)"
rate_limited: false
namespaces:
  users:
    - regex: '@.*:$DomainRe'
      exclusive: false
"@ | Set-Content -Encoding ascii "data\doublepuppet.yaml"

# --- Bridges -------------------------------------------------------------------------------
$AppServices = @("/data/appservices/doublepuppet.yaml")
foreach ($b in $Bridges) {
    Write-Host "== $b =="
    if (-not $Ports.ContainsKey($b)) { throw "Bridge desconocido: $b" }
    New-Item -ItemType Directory -Force -Path "data\$b" | Out-Null
    if (-not (Test-Path "data\$b\config.yaml")) { & docker compose run --rm --no-deps $b *> $null }
    if (-not (Test-Path "data\$b\config.yaml")) { throw "No se pudo generar data\$b\config.yaml" }

    $port = $Ports[$b]
    $vars = @{
        DOMAIN = $Domain; ADMIN_ID = $AdminId; DP_TOKEN = $DpToken; ID = $b; BOT = "${b}bot"
        ADDR = "http://${b}:$port"; PORT = "$port"; DB_URI = "postgres://unichat:$PgPass@postgres/${b}?sslmode=disable"
    }
    $common = '.homeserver.address = "http://synapse:8008" | .homeserver.domain = strenv(DOMAIN)' +
        ' | .appservice.address = strenv(ADDR) | .appservice.hostname = "0.0.0.0" | .appservice.port = env(PORT)' +
        ' | .appservice.id = strenv(ID)'
    $tgId = $env:TELEGRAM_API_ID
    if ($tgId) { $vars.TG_API_ID = $tgId; $vars.TG_API_HASH = "$env:TELEGRAM_API_HASH" }

    if ((Get-Yq "$b/config.yaml" 'has("network")') -eq "true") {
        # bridgev2 ("megabridge") layout
        $vars.PUPPET = "${b}_{{.}}"
        $expr = $common + ' | .appservice.bot.username = strenv(BOT) | .appservice.username_template = strenv(PUPPET)' +
            ' | .database.type = "postgres" | .database.uri = strenv(DB_URI)' +
            ' | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"' +
            ' | .bridge.permissions[strenv(ADMIN_ID)] = "admin"' +
            ' | .encryption.allow = false | .encryption.default = false | .encryption.require = false' +
            ' | .double_puppet.secrets = {} | .double_puppet.secrets[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)'
        if ($b -eq "instagram" -or $b -eq "facebook") { $vars.META_MODE = $b; $expr += ' | .network.mode = strenv(META_MODE)' }
        if ($b -eq "telegram" -and $tgId) { $expr += ' | .network.api_id = env(TG_API_ID) | .network.api_hash = strenv(TG_API_HASH)' }
    } elseif ((Get-Yq "$b/config.yaml" '.appservice | has("bot_username")') -eq "true") {
        # Legacy Python layout (mautrix-telegram)
        $vars.PUPPET = "${b}_{userid}"
        $vars.DB_URI = "postgres://unichat:$PgPass@postgres/$b"
        $expr = $common + ' | .appservice.bot_username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)' +
            ' | .appservice.database = strenv(DB_URI)' +
            ' | .bridge.permissions = {"*": "relaybot"} | .bridge.permissions[strenv(DOMAIN)] = "full"' +
            ' | .bridge.permissions[strenv(ADMIN_ID)] = "admin"' +
            ' | .bridge.encryption.allow = false | .bridge.encryption.default = false' +
            ' | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)'
        if ($tgId) { $expr += ' | .telegram.api_id = env(TG_API_ID) | .telegram.api_hash = strenv(TG_API_HASH)' }
    } else {
        # Legacy Go layout (mautrix-discord)
        $vars.PUPPET = "${b}_{{.}}"
        $expr = $common + ' | .appservice.bot.username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)' +
            ' | .appservice.database.type = "postgres" | .appservice.database.uri = strenv(DB_URI)' +
            ' | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"' +
            ' | .bridge.permissions[strenv(ADMIN_ID)] = "admin"' +
            ' | .bridge.encryption.allow = false | .bridge.encryption.default = false' +
            ' | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)'
    }
    Invoke-YqEdit "$b/config.yaml" $expr $vars

    # Delete registration.yaml to force regeneration after changing names.
    if (-not (Test-Path "data\$b\registration.yaml")) { & docker compose run --rm --no-deps $b *> $null }
    if (-not (Test-Path "data\$b\registration.yaml")) { throw "No se pudo generar data\$b\registration.yaml" }
    $AppServices += "/data/appservices/$b.yaml"
}

# Copy registrations where Synapse can read them.
$copy = "mkdir -p /data/synapse/appservices && cp /data/doublepuppet.yaml /data/synapse/appservices/ && " +
    (($Bridges | ForEach-Object { "cp /data/$_/registration.yaml /data/synapse/appservices/$_.yaml" }) -join " && ") +
    " && chmod 644 /data/synapse/appservices/*.yaml"
& docker run --rm --user root -v "${Data}:/data" --entrypoint sh $YqImage -c $copy
if ($LASTEXITCODE -ne 0) { throw "No se pudieron copiar los registros de los bridges" }

Invoke-YqEdit "synapse/homeserver.yaml" ('.database = {"name": "psycopg2", "args": {"user": "unichat",' +
    ' "password": strenv(PG_PASS), "database": "synapse", "host": "postgres", "cp_min": 5, "cp_max": 10}}' +
    ' | .enable_registration = false | .app_service_config_files = (strenv(APPSERVICES) | split(","))') `
    @{ PG_PASS = $PgPass; APPSERVICES = ($AppServices -join ",") }

# --- Start -----------------------------------------------------------------------------------
$profileArgs = @()
if ($Bridges -contains "linkedin") { $profileArgs = @("--profile", "linkedin") }
& docker compose @profileArgs up -d postgres synapse @Bridges

Write-Host "Esperando a Synapse..."
for ($i = 0; $i -lt 60; $i++) {
    try { Invoke-WebRequest -UseBasicParsing "http://localhost:8008/health" -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 2 }
}

$ans = Read-Host "¿Crear ahora el usuario $AdminId? [S/n]"
if ($ans -ne "n" -and $ans -ne "N") {
    & docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -u $AdminUser -a http://localhost:8008
    if ($LASTEXITCODE -ne 0) { Write-Host "(Si el usuario ya existía, ignora este error.)" }
}

Write-Host @"

Listo. En Unichat inicia sesión con:
  Servidor: http://localhost:8008   (o el dominio público si lo publicaste)
  Usuario:  $AdminUser
Luego ve a Ajustes > Cuentas para conectar cada red. Más detalles en docs\BRIDGES.md.
"@
