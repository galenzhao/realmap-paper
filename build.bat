@echo off
setlocal

:: Use JAVA_HOME if set, otherwise fall back to local JDK
if not defined JAVA_HOME set "JAVA_HOME=C:\Users\micro\.jdks\ms-25.0.3"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [RealMap] Building with Java: %JAVA_HOME%
call mvnw.cmd -q package
if errorlevel 1 (
  echo [RealMap] Build FAILED
  exit /b 1
)

copy /Y "target\realmap-1.0-SNAPSHOT.jar" "plugins\realmap.jar" >nul 2>&1
if not exist plugins mkdir plugins
copy /Y "target\realmap-1.0-SNAPSHOT.jar" "plugins\realmap.jar" >nul
echo [RealMap] Built and copied to plugins/realmap.jar
