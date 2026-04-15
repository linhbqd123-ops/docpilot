@echo off
setlocal enabledelayedexpansion

set JAVA_HOME=E:\IntelliJ IDEA 2026.1\jbr
set MVN_CMD="E:\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd"

echo Java Home: %JAVA_HOME%
echo.

cd /d "%~dp0"
%MVN_CMD% clean package -DskipTests

pause
