@echo off
chcp 65001 >nul
where py >nul 2>nul
if errorlevel 1 goto python
py -3 "%~dp0nnois.py" %*
exit /b %errorlevel%
:python
python "%~dp0nnois.py" %*
exit /b %errorlevel%
