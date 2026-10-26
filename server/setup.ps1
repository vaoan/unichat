# Generates Synapse + mautrix bridge configs and registrations for Unichat, then starts the stack (Windows).
# Usage: .\setup.ps1 [-Domain localhost] [-AdminUser admin] [-Bridges whatsapp,signal,...]
# Env:   TELEGRAM_API_ID / TELEGRAM_API_HASH (from https://my.telegram.org).
#        The admin user and the Telegram keys are saved to .env, so later runs reuse them.
# Safe to re-run: existing files are kept and only the Unichat-relevant keys are (re)applied.
# Keep in sync with setup.sh.
param(
    [string]$Domain = "",
    [string]$AdminUser = "",
    [string[]]$Bridges = @("whatsapp", "telegram", "signal", "instagram", "facebook", "discord", "slack",
                           "gmessages", "twitter", "linkedin")
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$YqImage = "mikefarah/yq:4"
$SynapseImage = "matrixdotorg/synapse:latest"
$Ports = @{ whatsapp = 29318; telegram = 29317; signal = 29328; instagram = 29319; facebook = 29320;
            discord = 29334; slack = 29335; gmessages = 29336; twitter = 29327; linkedin = 29341 }

# Runs a native command whose stderr may be redirected: Windows PowerShell 5.1 turns redirected
# stderr lines into terminating errors when $ErrorActionPreference is Stop.
function Invoke-Quiet([scriptblock]$Cmd) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Cmd *> $null } finally { $ErrorActionPreference = $old }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker no está instalado." }
Invoke-Quiet { docker info }
if ($LASTEXITCODE -ne 0) { throw "Docker no está en marcha (abre Docker Desktop)." }

function New-Secret([int]$Length = 32) {
    $chars = [char[]]"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    $bytes = New-Object byte[] $Length
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Read-EnvFile {
    $vars = @{}
    Get-Content ".env" | Where-Object { $_ -match "^\s*([^#=]+)=(.*)$" } | ForEach-Object { $vars[$Matches[1].Trim()] = $Matches[2] }
    $vars
}
# Sets (or with an empty value removes) KEY in .env.
function Set-EnvValue([string]$Key, [string]$Value) {
    $lines = @(Get-Content ".env" | Where-Object { $_ -notmatch "^$([regex]::Escape($Key))=" })
    if ($Value) { $lines += "$Key=$Value" }
    $lines | Set-Content -Encoding ascii ".env"
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
# Values given on the command line win over .env and are persisted.
if ($AdminUser) { Set-EnvValue "ADMIN_USER" $AdminUser }
if ($env:TELEGRAM_API_ID) { Set-EnvValue "TELEGRAM_API_ID" $env:TELEGRAM_API_ID }
if ($env:TELEGRAM_API_HASH) { Set-EnvValue "TELEGRAM_API_HASH" $env:TELEGRAM_API_HASH }
$EnvVars = Read-EnvFile
$Domain = $EnvVars["DOMAIN"]
$PgPass = $EnvVars["POSTGRES_PASSWORD"]
$DpToken = $EnvVars["DOUBLE_PUPPET_TOKEN"]
$AdminUser = if ($EnvVars["ADMIN_USER"]) { $EnvVars["ADMIN_USER"] } else { "admin" }
$TgId = $EnvVars["TELEGRAM_API_ID"]
$TgHash = $EnvVars["TELEGRAM_API_HASH"]
$env:POSTGRES_PASSWORD = $PgPass
$AdminId = "@${AdminUser}:$Domain"

# Telegram refuses to start without the user's own API keys: leave it out until they are set.
$TelegramOn = [bool]($TgId -and $TgHash)
$Active = @()
foreach ($b in $Bridges) {
    if (-not $Ports.ContainsKey($b)) { throw "Bridge desconocido: $b" }
    if ($b -eq "telegram" -and -not $TelegramOn) {
        Write-Host "Telegram queda desactivado: falta TELEGRAM_API_ID / TELEGRAM_API_HASH (ver docs\BRIDGES.md)."
        continue
    }
    $Active += $b
}
# The telegram service has the "telegram" compose profile; COMPOSE_PROFILES in .env enables it.
if ($TelegramOn) { Set-EnvValue "COMPOSE_PROFILES" "telegram"; $env:COMPOSE_PROFILES = "telegram" }
else { Set-EnvValue "COMPOSE_PROFILES" ""; Remove-Item Env:COMPOSE_PROFILES -ErrorAction SilentlyContinue }

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
# Runs a shell command (no double quotes!) in the yq container with .\data mounted, as root.
function Invoke-DataShell([string]$Script) {
    (& docker run --rm --user root -v "${Data}:/data" --entrypoint sh $YqImage -c $Script | Out-String).Trim()
}
# Checksum of files under .\data (container paths, globs allowed).
function Get-DataSum([string]$Paths) { Invoke-DataShell "cat $Paths 2>/dev/null | md5sum" }
# Checksum of a YAML file's content, ignoring comments and formatting (bridges rewrite their config on start).
function Get-ConfSum([string]$File) { Invoke-DataShell "yq -o=json . /data/$File | md5sum" }

# --- Synapse -------------------------------------------------------------------------------
if (-not (Test-Path "data/synapse/homeserver.yaml")) {
    Write-Host "Generando configuración de Synapse para $Domain..."
    New-Item -ItemType Directory -Force -Path "data/synapse" | Out-Null
    & docker run --rm -v "$(Join-Path $Data "synapse"):/data" -e "SYNAPSE_SERVER_NAME=$Domain" -e "SYNAPSE_REPORT_STATS=no" $SynapseImage generate
    if ($LASTEXITCODE -ne 0) { throw "No se pudo generar la configuración de Synapse" }
}
$SynapseFiles = "/data/synapse/homeserver.yaml /data/synapse/appservices/*.yaml"
$SynapseSumBefore = Get-DataSum $SynapseFiles

# --- Double puppeting ----------------------------------------------------------------------
# Written once: rewriting it with new random tokens would force a Synapse restart on every run.
if (-not (Test-Path "data/doublepuppet.yaml")) {
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
"@ | Set-Content -Encoding ascii "data/doublepuppet.yaml"
}

# --- Bridges -------------------------------------------------------------------------------
$AppServices = @("/data/appservices/doublepuppet.yaml")
$Changed = @()
foreach ($b in $Active) {
    Write-Host "== $b =="
    New-Item -ItemType Directory -Force -Path "data/$b" | Out-Null
    if (-not (Test-Path "data/$b/config.yaml")) { Invoke-Quiet { docker compose run --rm --no-deps $b } }
    if (-not (Test-Path "data/$b/config.yaml")) { throw "No se pudo generar data/$b/config.yaml" }
    $before = Get-ConfSum "$b/config.yaml"

    $port = $Ports[$b]
    $vars = @{
        DOMAIN = $Domain; ADMIN_ID = $AdminId; DP_TOKEN = $DpToken; ID = $b; BOT = "${b}bot"; PUPPET = "${b}_{{.}}"
        ADDR = "http://${b}:$port"; PORT = "$port"; DB_URI = "postgres://unichat:$PgPass@postgres/${b}?sslmode=disable"
    }
    # Command prefix "!<id>" (e.g. !whatsapp): Unichat prefixes every bot command with it, so commands also
    # work in a bot DM that is not the bridge's management room. Keep in sync with Network.protocolId().
    $common = '.homeserver.address = "http://synapse:8008" | .homeserver.domain = strenv(DOMAIN)' +
        ' | .appservice.address = strenv(ADDR) | .appservice.hostname = "0.0.0.0" | .appservice.port = env(PORT)' +
        ' | .appservice.id = strenv(ID) | .bridge.command_prefix = "!" + strenv(ID)'

    if ((Get-Yq "$b/config.yaml" 'has("network")') -eq "true") {
        # bridgev2 ("megabridge") layout: every bridge except discord
        $expr = $common + ' | .appservice.bot.username = strenv(BOT) | .appservice.username_template = strenv(PUPPET)' +
            ' | .database.type = "postgres" | .database.uri = strenv(DB_URI)' +
            ' | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"' +
            ' | .bridge.permissions[strenv(ADMIN_ID)] = "admin"' +
            ' | .encryption.allow = false | .encryption.default = false | .encryption.require = false' +
            ' | .double_puppet.secrets = {} | .double_puppet.secrets[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)' +
            ' | .backfill.enabled = true | .backfill.max_initial_messages = 50 | .backfill.max_catchup_messages = 500'
        # Instagram runs the pinned mautrix-meta v26.07 (last release with Instagram) in instagram mode.
        # Current mautrix-meta (facebook) has no mode key: its login flows (facebook, messenger, ...) pick it.
        if ($b -eq "instagram") {
            $expr += ' | .network.mode = "instagram" | .appservice.bot.displayname = "Instagram bridge bot"'
        }
        if ($b -eq "telegram") {
            $vars.TG_API_ID = $TgId; $vars.TG_API_HASH = $TgHash
            $expr += ' | .network.api_id = env(TG_API_ID) | .network.api_hash = strenv(TG_API_HASH)'
        }
    } else {
        # Legacy Go layout (mautrix-discord)
        $expr = $common + ' | .appservice.bot.username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)' +
            ' | .appservice.database.type = "postgres" | .appservice.database.uri = strenv(DB_URI)' +
            ' | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"' +
            ' | .bridge.permissions[strenv(ADMIN_ID)] = "admin"' +
            ' | .bridge.encryption.allow = false | .bridge.encryption.default = false' +
            ' | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)' +
            ' | .bridge.backfill.forward_limits.initial.dm = 50 | .bridge.backfill.forward_limits.initial.channel = 50' +
            ' | .bridge.backfill.forward_limits.missed.dm = 500 | .bridge.backfill.forward_limits.missed.channel = 500'
    }
    Invoke-YqEdit "$b/config.yaml" $expr $vars
    if ($before -ne (Get-ConfSum "$b/config.yaml")) { $Changed += $b }

    # Delete registration.yaml to force regeneration after changing names.
    if (-not (Test-Path "data/$b/registration.yaml")) { Invoke-Quiet { docker compose run --rm --no-deps $b } }
    if (-not (Test-Path "data/$b/registration.yaml")) { throw "No se pudo generar data/$b/registration.yaml" }
    $AppServices += "/data/appservices/$b.yaml"
}

# Copy registrations where Synapse can read them (bridge files may be 0600 and owned by the bridge uid).
# Registrations of disabled bridges are removed so Synapse does not queue events for them.
$copy = "rm -rf /data/synapse/appservices && mkdir -p /data/synapse/appservices" +
    " && cp /data/doublepuppet.yaml /data/synapse/appservices/doublepuppet.yaml" +
    (($Active | ForEach-Object { " && cp /data/$_/registration.yaml /data/synapse/appservices/$_.yaml" }) -join "") +
    " && chmod 644 /data/synapse/appservices/*.yaml"
& docker run --rm --user root -v "${Data}:/data" --entrypoint sh $YqImage -c $copy
if ($LASTEXITCODE -ne 0) { throw "No se pudieron copiar los registros de los bridges" }

# Personal server: the default message/login rate limits are too tight when connecting several bridges
# in a row (createRoom counts as a message). Failed-login limits stay at Synapse's defaults.
Invoke-YqEdit "synapse/homeserver.yaml" ('.database = {"name": "psycopg2", "args": {"user": "unichat",' +
    ' "password": strenv(PG_PASS), "database": "synapse", "host": "postgres", "cp_min": 5, "cp_max": 10}}' +
    ' | .enable_registration = false | .rc_message = {"per_second": 10, "burst_count": 100}' +
    ' | .rc_login.address = {"per_second": 1, "burst_count": 20} | .rc_login.account = {"per_second": 1, "burst_count": 20}' +
    ' | .app_service_config_files = (strenv(APPSERVICES) | split(","))') `
    @{ PG_PASS = $PgPass; APPSERVICES = ($AppServices -join ",") }
if ($SynapseSumBefore -ne (Get-DataSum $SynapseFiles)) { $Changed = @("synapse") + $Changed }

# --- Start -----------------------------------------------------------------------------------
if (-not $TelegramOn) { Invoke-Quiet { docker compose --profile telegram rm -sf telegram } }
# Containers that were already running keep the old config until restarted (new ones read it fresh).
$Running = @(& docker compose ps --services --status running)
$Restart = @($Changed | Where-Object { $Running -contains $_ })
Write-Host "Arrancando contenedores (la primera vez Synapse tarda ~1 min)..."
& docker compose up -d postgres synapse @Active
if ($LASTEXITCODE -ne 0) { throw "docker compose up falló" }
if ($Restart.Count -gt 0) {
    Write-Host "Reiniciando por cambios de configuración: $($Restart -join ' ')"
    & docker compose restart @Restart
}

Write-Host "Esperando a Synapse..."
for ($i = 0; $i -lt 60; $i++) {
    try { Invoke-WebRequest -UseBasicParsing "http://localhost:8008/health" -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 2 }
}

# --- User account ----------------------------------------------------------------------------
# Created once with a random password, saved to CREDENCIALES.txt (git-ignored).
if (-not (Test-Path "CREDENCIALES.txt")) {
    $Password = New-Secret 24
    Invoke-Quiet { docker compose exec -T synapse register_new_matrix_user -c /data/homeserver.yaml -u $AdminUser -p $Password -a http://localhost:8008 }
    if ($LASTEXITCODE -eq 0) {
        @("Unichat / Matrix", "Servidor:   http://localhost:8008", "Usuario:    $AdminUser   ($AdminId)",
          "Contraseña: $Password") | Set-Content -Encoding UTF8 "CREDENCIALES.txt"
        Write-Host "Usuario $AdminId creado. Contraseña guardada en server\CREDENCIALES.txt"
    } else {
        Write-Host "No se pudo crear $AdminId (¿ya existe?). Crea otro con:"
        Write-Host "  docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -a http://localhost:8008"
    }
}

Write-Host @"

Listo. En Unichat inicia sesión con:
  Servidor: http://localhost:8008   (o el dominio público si lo publicaste)
  Usuario:  $AdminUser   (contraseña en server\CREDENCIALES.txt)
Luego usa "Conectar una red" para cada red. Más detalles en docs\BRIDGES.md.
"@
