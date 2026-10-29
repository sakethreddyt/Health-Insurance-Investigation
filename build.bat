@echo off
rem Put this file in E:\casedesk-project and double-click it (or run it from Command Prompt).
cd /d "%~dp0"

echo [1/3] Stopping running Java programs (the old Case Desk app, if it is running)...
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM javaw.exe >nul 2>&1
timeout /t 3 /nobreak >nul

echo [2/3] Deleting the old build folder...
if exist target rmdir /s /q target
if exist target (
  echo.
  echo The "target" folder is still locked by something else.
  echo Close File Explorer windows showing it and any IDE with this project open, then run this again.
  echo If it still fails, restart the PC and run this file first, before opening anything else.
  pause
  exit /b 1
)

echo [3/3] Building...
call mvn package -DskipTests
if errorlevel 1 (
  echo.
  echo BUILD FAILED - copy the [ERROR] lines above and send them to me.
  pause
  exit /b 1
)

echo.
echo BUILD OK.  Start the app with:  java -jar target\casedesk.jar
pause
