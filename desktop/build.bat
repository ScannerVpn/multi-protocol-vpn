@echo off
setlocal enabledelayedexpansion
title MultiVPN Build

rem ================================================================
rem  MultiVPN - one-shot Windows build script (v3.6.6)
rem  Produces a PORTABLE EXE you can run right away, no installer:
rem    build\compose\binaries\main\app\MultiVPN\MultiVPN.exe
rem  Steps: find JDK -> fetch VPN cores if missing -> createDistributable
rem  MSI/EXE *installers* additionally need WiX 3.x (see note at end).
rem ================================================================

cd /d "%~dp0"

echo.
echo ==========================================
echo   MultiVPN Build Script
echo ==========================================

rem ---------- [1/4] locate a usable JDK (17+) --------------------------
set "JDK_OK=0"
if defined JAVA_HOME if exist "!JAVA_HOME!\bin\java.exe" set "JDK_OK=1"

if "!JDK_OK!"=="0" (
    for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do (
        if exist "%%D\bin\java.exe" (
            set "JAVA_HOME=%%D"
            set "JDK_OK=1"
        )
    )
)

if "!JDK_OK!"=="0" (
    where java >nul 2>&1
    if not errorlevel 1 set "JDK_OK=1"
)

if not "!JDK_OK!"=="1" (
    echo   ERROR: no Java found on this machine.
    echo   Install JDK 17+ ^(Temurin^): https://adoptium.net/?variant=openjdk17
    echo   ...or point JAVA_HOME at an existing JDK 17 folder and re-run.
    goto :fail
)

if defined JAVA_HOME set "PATH=!JAVA_HOME!\bin;%PATH%"

echo [1/4] Using Java at '!JAVA_HOME!' or system PATH:
java -version 2>&1 | findstr /i "version"
if errorlevel 1 goto :fail_java

rem ---------- [2/4] VPN cores are NOT in git - fetch when missing ------
if exist "src\main\resources\bin\xray\xray.exe" (
    echo [2/4] Cores already present - skipping download.
) else (
    echo [2/4] Downloading VPN cores ^(xray / sing-box / openvpn^) ...
    powershell -NoProfile -ExecutionPolicy Bypass -File fetch-cores.ps1 -SkipWireproxy
    if errorlevel 1 (
        echo   WARNING: core download failed. The app will still build,
        echo   but cannot CONNECT to anything until cores exist.
        echo   Re-run this script when you have internet.
    )
)

rem ---------- [3/4] package the portable app ---------------------------
if /i "%~1"=="clean" (
    echo [3/4] Gradle clean + createDistributable ...
    call gradlew.bat --no-daemon clean createDistributable
) else (
    echo [3/4] Gradle createDistributable ...
    call gradlew.bat --no-daemon createDistributable
)
if errorlevel 1 (
    echo.
    echo   BUILD FAILED ^(!errorlevel!^).
    echo   * "Unable to delete directory"? Close the running MultiVPN.exe
    echo     and re-run this script. It is safe to just retry.
    echo   * First run downloads dependencies - make sure internet works.
    goto :fail
)

rem ---------- [4/4] verify + report actual outputs ----------------------
set "PORTABLE=%cd%\build\compose\binaries\main\app\MultiVPN"
if not exist "%PORTABLE%\MultiVPN.exe" (
    echo   ERROR: expected executable was NOT produced:
    echo     %PORTABLE%\MultiVPN.exe
    goto :fail
)

echo.
echo ==========================================
echo   BUILD OK
echo ==========================================
echo.
echo   Run this (no installation needed):
echo     %PORTABLE%\MultiVPN.exe
echo.
for %%F in ("build\compose\binaries\main\msi\*.msi") do echo   Installer found: %%~fF
for %%F in ("build\compose\binaries\main\exe\*.exe") do echo   Installer found: %%~fF
if not exist "src\main\resources\bin\wireproxy\wireproxy.exe" (
    echo.
    echo   NOTE: WireGuard/AmneziaWG protocols are OFFLINE in this build
    echo   ^(no wireproxy.exe^). Install Go, then run:
    echo       powershell -File fetch-cores.ps1
    echo   ...and rebuild to enable them.
)
echo.
echo   To also get installers, WITH WiX 3.x installed ^(
echo   https://wixtoolset.org/ ^):   gradlew.bat --no-daemon packageMsi packageExe
echo.
start "" explorer /select,"%PORTABLE%\MultiVPN.exe"
pause
exit /b 0

:fail_java
echo   ERROR: java.exe failed to start even though it was located -
echo   the JDK is probably broken/incomplete. Re-install JDK 17+.
goto :fail

:fail
echo.
pause
exit /b 1
