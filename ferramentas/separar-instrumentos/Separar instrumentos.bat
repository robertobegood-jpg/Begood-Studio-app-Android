@echo off
rem Arraste musicas para este arquivo (ou de dois cliques para escolher).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0separar.ps1" %*
pause
