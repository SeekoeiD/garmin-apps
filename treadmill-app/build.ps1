# Build Treadmill Link for the Forerunner 965.
#
#   .\build.ps1            # build bin\TreadmillLink.prg
#   .\build.ps1 -Test      # build bin\TreadmillTest.prg (unit tests)
#   .\build.ps1 -Run       # build, then load into a running simulator
#   .\build.ps1 -Test -Run # build tests, then run them in the simulator
#
# The simulator must already be running for -Run; start it with:
#   & $Sdk\bin\connectiq.bat

param(
    [switch]$Test,
    [switch]$Run,
    [string]$Sdk = "C:\Users\User\Documents\edge-music-control\tools\connectiq-sdk",
    [string]$Key = "C:\Users\User\Documents\edge-music-control\tools\developer_key.der",
    [string]$Device = "fr965"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path $Key)) {
    throw "Developer key not found at $Key. Pass -Key <path> or generate one with openssl."
}

New-Item -ItemType Directory -Force -Path bin | Out-Null

$output = if ($Test) { "bin\TreadmillTest.prg" } else { "bin\TreadmillLink.prg" }
$args = @("-o", $output, "-f", "monkey.jungle", "-y", $Key, "-d", $Device, "-w")

if ($Test) {
    $args = @("--unit-test") + $args
}

& "$Sdk\bin\monkeyc.bat" @args

if ($LASTEXITCODE -ne 0) {
    throw "Build failed"
}

Write-Host "Built $output" -ForegroundColor Green

if ($Run) {
    if (-not (Get-Process simulator -ErrorAction SilentlyContinue)) {
        throw "Simulator is not running. Start it with: & `"$Sdk\bin\connectiq.bat`""
    }

    if ($Test) {
        & "$Sdk\bin\monkeydo.bat" $output $Device /t
    } else {
        & "$Sdk\bin\monkeydo.bat" $output $Device
    }
}
