@echo off
setlocal
cd /d "%~dp0"

rem Resolve Python per-machine: %LOCALAPPDATA% works for any username
rem (the old hardcoded C:\Users\ediso\... broke on the new PC).
set PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe
if not exist "%PY%" set PY=python

if "%~1"=="" goto menu
"%PY%" client.py %*
goto :eof

:menu
title Shadow Client
echo =============================================
echo     SHADOW CLIENT  -  tuned Minecraft launcher
echo =============================================
echo   1. First-time setup (download + install mods + build HUD)
echo   2. Launch Minecraft
echo   3. Import account from PrismLauncher (for online play)
echo   4. Update performance mods
echo   5. Rebuild Shadow HUD (FPS/coords/biome overlay)
echo   6. Doctor (diagnose chat / launch problems)
echo   7. Doctor + fix (auto-refresh expired chat token; close MC first)
echo   8. Exit
echo.
set /p choice="Pick: "
if "%choice%"=="1" "%PY%" client.py setup
if "%choice%"=="2" "%PY%" client.py launch
if "%choice%"=="3" "%PY%" client.py login
if "%choice%"=="4" "%PY%" client.py update-mods
if "%choice%"=="5" "%PY%" client.py build-hud
if "%choice%"=="6" "%PY%" client.py doctor
if "%choice%"=="7" "%PY%" client.py doctor --fix
if "%choice%"=="8" goto :eof
echo.
pause
goto menu
