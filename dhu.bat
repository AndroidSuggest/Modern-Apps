@echo off
rem Windows entry point for ./dhu - delegates to dhu.ps1 (PowerShell).
rem Mirrors the install / install.bat pattern so `./dhu` works on Windows
rem (cmd and PowerShell) just like the bash `dhu` does on macOS/Linux.
setlocal
set "DIR=%~dp0"
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%DIR%dhu.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%DIR%dhu.ps1" %*
)
exit /b %ERRORLEVEL%
