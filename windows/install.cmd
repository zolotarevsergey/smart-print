@echo off
rem SportikPrint setup: double-click and allow administrator rights.
rem Options: install.cmd -AsUser   or   install.cmd -Uninstall
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
pause
