@echo off
rem Windows entry point for ./install — delegates to install.ps1 (PowerShell).
rem Mirrors the gradlew / gradlew.bat pattern so `./install x y z` works on
rem Windows (cmd and PowerShell) just like the bash `install` does on macOS/Linux.
setlocal
set "DIR=%~dp0"
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%DIR%install.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%DIR%install.ps1" %*
)
exit /b %ERRORLEVEL%
