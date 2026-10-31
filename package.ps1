# Builds Unichat for Windows with jpackage (JDK 21+).
# Output in .\release: Unichat\ (portable folder, Unichat.exe inside), Unichat-<ver>.zip,
# and Unichat-<ver>.msi when the WiX Toolset is installed (https://wixtoolset.org).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$Version = "1.0.0"

.\mvnw.cmd -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Remove-Item -Recurse -Force target\pkg, release -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force target\pkg, release | Out-Null
Copy-Item target\unichat.jar target\pkg\

# Animated stickers for chats that only take WebP (WhatsApp): libwebp's gif2webp, pinned (see StickerConverter
# and native/fetch-gif2webp.sh for the macOS build of the same release).
$WebpVersion = "1.6.0"
$WebpAsset = "libwebp-$WebpVersion-windows-x64.zip"
$WebpSha256 = "48886f506b21f62e4661f0f4cbfca19800897c385128e8902542d29a950c93f1"
try {
    $zip = "target\$WebpAsset"
    Invoke-WebRequest "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/$WebpAsset" -OutFile $zip
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $WebpSha256) { throw "libwebp checksum mismatch" }
    Expand-Archive $zip -DestinationPath target\libwebp -Force
    New-Item -ItemType Directory -Force target\pkg\server | Out-Null
    Copy-Item "target\libwebp\libwebp-$WebpVersion-windows-x64\bin\gif2webp.exe" target\pkg\server\
} catch {
    Write-Warning "Sin gif2webp los stickers animados se envían fijos a WhatsApp: $_"
}

# Voice notes to text (stt/Transcriber): whisper.cpp's official Windows build of the release that
# native/build-whisper.sh compiles for macOS (v1.9.5 = build b5454). Only whisper-cli and the DLLs it loads; ggml
# picks the ggml-cpu-*.dll matching the processor.
$WhisperBuild = "b5454"
$WhisperSha256 = "6ba69e3482d7826214f90a6a9c84ca07782aec1e1d0c6a7c30c994fd5d816ccb"
try {
    $zip = "target\whisper-bin-x64.zip"
    Invoke-WebRequest "https://github.com/ggml-org/whisper.cpp/releases/download/$WhisperBuild/whisper-bin-x64.zip" -OutFile $zip
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $WhisperSha256) { throw "whisper.cpp checksum mismatch" }
    Expand-Archive $zip -DestinationPath target\whisper -Force
    New-Item -ItemType Directory -Force target\pkg\server\whisper | Out-Null
    Copy-Item target\whisper\Release\whisper-cli.exe, target\whisper\Release\whisper.dll, target\whisper\Release\ggml.dll, `
        target\whisper\Release\ggml-base.dll, target\whisper\Release\ggml-cpu-*.dll target\pkg\server\whisper\
} catch {
    Write-Warning "Sin whisper no se pueden transcribir notas de voz (salvo con un comando propio): $_"
}

$common = @("--name", "Unichat", "--app-version", $Version, "--vendor", "Unichat",
            "--input", "target\pkg", "--main-jar", "unichat.jar", "--main-class", "app.unichat.Launcher",
            "--dest", "release", "--icon", "packaging\unichat.ico", "--java-options", "-Xmx1g")

jpackage @common --type app-image
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed" }
Compress-Archive -Path release\Unichat -DestinationPath "release\Unichat-$Version-windows.zip" -Force

if (Get-Command candle.exe -ErrorAction SilentlyContinue) {
    jpackage @common --type msi --win-menu --win-shortcut --win-dir-chooser --win-per-user-install `
        --win-upgrade-uuid "6f1c3a52-7d0e-4c55-9b1e-2f5d7c8a9e10"
} else {
    Write-Host "WiX not found: skipping .msi (the portable zip is ready)."
}
Get-ChildItem release
