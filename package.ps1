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

$common = @("--name", "Unichat", "--app-version", $Version, "--vendor", "Unichat",
            "--input", "target\pkg", "--main-jar", "unichat.jar", "--main-class", "app.unichat.Launcher",
            "--dest", "release", "--icon", "packaging\unichat.ico", "--java-options", "-Xmx512m")

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
