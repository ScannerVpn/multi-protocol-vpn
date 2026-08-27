@echo off
echo ========================================
echo   MultiVPN Build Script
echo ========================================
cd /d "%~dp0"
call gradlew.bat clean build -x test --no-daemon
if %errorlevel% neq 0 (
    echo.
    echo !!! Build failed (exit code: %errorlevel%) !!!
    pause
    exit /b %errorlevel%
)
echo.
echo ========================================
echo   Build successful!
echo ========================================
echo.
echo EXE output:
echo   build\compose\binaries\app\MultiVPN\windows-exe\MultiVPN.exe
echo.
pause
