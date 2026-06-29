@echo off
setlocal

:: Use JAVA_HOME if set, otherwise fall back to local JDK
if not defined JAVA_HOME set "JAVA_HOME=C:\Users\micro\.jdks\ms-25.0.3"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: Heap size — Xms and Xmx must match (Paper recommendation)
if not defined SERVER_MEMORY set "SERVER_MEMORY=2G"

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

:: JVM flags recommended by Fill for Paper 26.1 / Java 25:
:: https://fill-ui.papermc.io/projects/paper/family/26.1
"%JAVA_HOME%\bin\java" ^
  -Xms%SERVER_MEMORY% -Xmx%SERVER_MEMORY% ^
  -XX:+AlwaysPreTouch ^
  -XX:+DisableExplicitGC ^
  -XX:+ParallelRefProcEnabled ^
  -XX:+PerfDisableSharedMem ^
  -XX:+UnlockExperimentalVMOptions ^
  -XX:+UseG1GC ^
  -XX:G1HeapRegionSize=8M ^
  -XX:G1HeapWastePercent=5 ^
  -XX:G1MaxNewSizePercent=40 ^
  -XX:G1MixedGCCountTarget=4 ^
  -XX:G1MixedGCLiveThresholdPercent=90 ^
  -XX:G1NewSizePercent=30 ^
  -XX:G1RSetUpdatingPauseTimePercent=5 ^
  -XX:G1ReservePercent=20 ^
  -XX:InitiatingHeapOccupancyPercent=15 ^
  -XX:MaxGCPauseMillis=200 ^
  -XX:MaxTenuringThreshold=1 ^
  -XX:SurvivorRatio=32 ^
  -jar paper-26.1.2-72.jar ^
  --nogui
