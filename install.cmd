@REM ---------------------------------------------------------------------------
@REM Installs orkx for the current user and puts it on PATH.
@REM
@REM     install.cmd                 install, building the binary first if needed
@REM     install.cmd -Uninstall      remove it again, PATH entry included
@REM     install.cmd -NoPathChange   install the binary, leave PATH alone
@REM
@REM The work is in install.ps1: editing PATH from batch means setx, which
@REM truncates it at 1024 characters. This is the part you type, and it passes
@REM -ExecutionPolicy Bypass so a default Windows policy cannot get in the way.
@REM ---------------------------------------------------------------------------
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %ERRORLEVEL%
