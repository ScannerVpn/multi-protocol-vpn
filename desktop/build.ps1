# Build script for MultiVPN
# Run from PowerShell: .\build.ps1
$ErrorActionPreference = "Stop"

Write-Host "Building MultiVPN..." -ForegroundColor Cyan

cd (Split-Path $PSScriptRoot -Parent)

# Use gradlew wrapper
& ".\gradlew.bat" clean build -x test --no-daemon

if ($LASTEXITCODE -eq 0) {
    Write-Host "" -ForegroundColor Green
    Write-Host "Build successful!" -ForegroundColor Green
    Write-Host "EXE output: desktop\build\compose\binaries\app\MultiVPN\windows-exe\MultiVPN.exe" -ForegroundColor Cyan
} else {
    Write-Host "Build failed (exit code: $LASTEXITCODE)" -ForegroundColor Red
    exit $LASTEXITCODE
}
