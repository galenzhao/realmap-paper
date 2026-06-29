@echo off
setlocal

:: Use JAVA_HOME if set, otherwise fall back to local JDK
if not defined JAVA_HOME set "JAVA_HOME=C:\Users\micro\.jdks\ms-25.0.3"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: Copy latest built plugin jar to plugins folder
if not exist plugins mkdir plugins
if exist "target\realmap-1.0-SNAPSHOT.jar" (
  copy /Y "target\realmap-1.0-SNAPSHOT.jar" "plugins\realmap.jar" >nul
  echo [RealMap] Plugin copied to plugins/
) else (
  echo [RealMap] No built jar found — run build.bat first
)

:: Dev full wipe BEFORE the JVM opens world files (plugin clear alone cannot
:: delete overworld regions while the server is running).
if exist "plugins\RealMap\config.yml" (
  findstr /R /C:"clear_on_startup: *true" "plugins\RealMap\config.yml" >nul 2>&1
) else (
  findstr /R /C:"clear_on_startup: *true" "src\main\resources\config.yml" >nul 2>&1
)
if %ERRORLEVEL%==0 (
  echo [DEV] clear_on_startup=true — wiping world and RealMap data...
  if exist world rmdir /s /q world
  if exist plugins\RealMap\staging rmdir /s /q plugins\RealMap\staging
  del /q plugins\RealMap\tiles.db 2>nul
  del /q plugins\RealMap\tiles.db-wal 2>nul
  del /q plugins\RealMap\tiles.db-shm 2>nul
  echo [DEV] Pre-start wipe complete.
)

:: Start Paper server
if not exist "paper-26.1.2-72.jar" (
  echo [ERROR] paper-26.1.2-72.jar not found. See README.md for download instructions.
  exit /b 1
)

"%JAVA_HOME%\bin\java" ^
  -Xms1G -Xmx2G ^
  -jar paper-26.1.2-72.jar ^
  --nogui
