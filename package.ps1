# Builds Unichat for Windows with jpackage (JDK 25+).
# Output in .\release: Unichat\ (portable folder, Unichat.exe inside), Unichat-<ver>.zip,
# and Unichat-<ver>.msi when the WiX Toolset is installed (https://wixtoolset.org).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
# The version is the pom's (also the app's: support/AppVersion), so a release changes it in one place.
$Version = ([xml](Get-Content pom.xml)).project.version

.\mvnw.cmd -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Remove-Item -Recurse -Force target\pkg, release -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force target\pkg, release | Out-Null
Copy-Item target\unichat.jar, LICENSE, THIRD-PARTY.md target\pkg\
# What leaves the computer and the license: shown by the .msi before installing (SignPath's terms), and kept beside the app.
Copy-Item packaging\install-notice.txt target\pkg\PRIVACY.txt

# The local server (LocalServer): the homeserver ships inside the app; the bridges are downloaded on first use. Both
# come from Unichat's own release (NATIVES in versions.env, built by .github/workflows/natives.yml), since there are
# no official Windows builds. $env:TUWUNEL_BIN=<tuwunel.exe> wins.
New-Item -ItemType Directory -Force target\pkg\server | Out-Null
try {
    if ($env:TUWUNEL_BIN) {
        Copy-Item $env:TUWUNEL_BIN target\pkg\server\tuwunel.exe
    } else {
        $natives = (Select-String -Path src\main\resources\app\unichat\server\versions.env -Pattern '^NATIVES=(.+)$').Matches[0].Groups[1].Value
        $repo, $tag = $natives -split '@', 2
        $asset = "tuwunel-windows-amd64"
        # Checked against the SHA-256 pinned in the app (native/pin-hashes.sh), never the release's own sha256sums.txt.
        $key = "$natives/$asset"
        $pin = Get-Content src\main\resources\app\unichat\server\sha256sums.txt |
            Where-Object { ($_ -split '\s+', 2)[1] -ceq $key } | Select-Object -First 1
        if (-not $pin) { throw "$key has no pinned SHA-256: run native/pin-hashes.sh" }
        $want = ($pin -split '\s+')[0].ToLower()
        Remove-Item -Recurse -Force target\natives -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force target\natives | Out-Null
        if ((Get-Command gh -ErrorAction SilentlyContinue) -and $env:GH_TOKEN) {
            gh release download $tag -R $repo -D target\natives -p $asset
            if ($LASTEXITCODE -ne 0) { throw "gh release download failed" }
        } else {
            Invoke-WebRequest "https://github.com/$repo/releases/download/$tag/$asset" -OutFile "target\natives\$asset"
        }
        if ((Get-FileHash "target\natives\$asset" -Algorithm SHA256).Hash.ToLower() -ne $want) {
            throw "tuwunel checksum mismatch"
        }
        Copy-Item "target\natives\$asset" target\pkg\server\tuwunel.exe
    }
} catch {
    Write-Warning "Without tuwunel the app has no local server (external servers only): $_"
}

# Animated stickers for chats that only take WebP (WhatsApp): libwebp's gif2webp, pinned (see StickerConverter
# and native/build-gif2webp.sh for the macOS build of the same release).
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
    # Animated stickers are decoded with libwebp's anim_dump (video/AnimDump): the Java decoder gets some frames wrong.
    Copy-Item "target\libwebp\libwebp-$WebpVersion-windows-x64\bin\anim_dump.exe" target\pkg\server\
    # A GIF that came as an MP4 is forwarded as an animated WebP made from its frames (video/GifVideos).
    Copy-Item "target\libwebp\libwebp-$WebpVersion-windows-x64\bin\img2webp.exe" target\pkg\server\
} catch {
    Write-Warning "Without gif2webp, animated stickers go to WhatsApp as still images: $_"
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
    Write-Warning "Without whisper, voice notes can't be transcribed (except with your own command): $_"
}

# Name, version, description and copyright end up in Unichat.exe's file details: SignPath checks them when signing.
$common = @("--name", "Unichat", "--app-version", $Version, "--vendor", "Unichat",
            "--description", "Unichat", "--copyright", "Copyright (c) 2026 vaoan",
            "--input", "target\pkg", "--main-jar", "unichat.jar", "--main-class", "app.unichat.Launcher",
            "--dest", "release", "--icon", "packaging\unichat.ico", "--java-options", "-Xmx1g")

jpackage @common --type app-image
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed" }
Compress-Archive -Path release\Unichat -DestinationPath "release\Unichat-$Version-windows.zip" -Force

if (Get-Command candle.exe -ErrorAction SilentlyContinue) {
    jpackage @common --type msi --license-file packaging\install-notice.txt --win-menu --win-shortcut --win-dir-chooser --win-per-user-install `
        --win-upgrade-uuid "6f1c3a52-7d0e-4c55-9b1e-2f5d7c8a9e10"
} else {
    Write-Host "WiX not found: skipping .msi (the portable zip is ready)."
}
Get-ChildItem release
