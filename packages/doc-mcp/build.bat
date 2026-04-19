@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "BUILD_MODE="
set "MVN_ARGS="

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="win-app" (
	set "BUILD_MODE=win-app"
	shift
	goto parse_args
)
REM --bundle-qdrant option removed (personalization/qdrant no longer supported)
set "MVN_ARGS=!MVN_ARGS! %~1"
shift
goto parse_args

:args_done

set "MVN_CMD="
if exist "%SCRIPT_DIR%mvnw.cmd" set "MVN_CMD=%SCRIPT_DIR%mvnw.cmd"
if not defined MVN_CMD for %%I in (mvn.cmd mvn) do (
	if not defined MVN_CMD if exist "%%~$PATH:I" set "MVN_CMD=%%~$PATH:I"
)
if not defined MVN_CMD for /d %%D in ("E:\IntelliJ IDEA *" "%ProgramFiles%\JetBrains\IntelliJ IDEA *" "%LocalAppData%\Programs\IntelliJ IDEA *") do (
	if not defined MVN_CMD if exist "%%~fD\plugins\maven\lib\maven3\bin\mvn.cmd" set "MVN_CMD=%%~fD\plugins\maven\lib\maven3\bin\mvn.cmd"
	if "%JAVA_HOME%"=="" if exist "%%~fD\jbr\bin\java.exe" set "JAVA_HOME=%%~fD\jbr"
)

if not defined MVN_CMD (
	echo Maven not found. Install Maven or add it to PATH.
	exit /b 1
)

if "%JAVA_HOME%"=="" (
	echo JAVA_HOME is not set. Point it to a JDK or IntelliJ JBR.
	exit /b 1
)

if "%DOC_MCP_DATA_DIR%"=="" (
	if not "%LOCALAPPDATA%"=="" (
		set "DOC_MCP_DATA_DIR=%LOCALAPPDATA%\DocPilot\mcp"
	) else (
		set "DOC_MCP_DATA_DIR=%USERPROFILE%\.docpilot\mcp"
	)
)

echo Maven: %MVN_CMD%
echo Data Dir: %DOC_MCP_DATA_DIR%
echo.

cd /d "%SCRIPT_DIR%"
call "%MVN_CMD%" clean package -DskipTests %MVN_ARGS%
if errorlevel 1 exit /b 1

if /I not "%BUILD_MODE%"=="win-app" goto end

set "JPACKAGE_CMD="
for %%I in (jpackage.exe jpackage) do (
	if not defined JPACKAGE_CMD if exist "%%~$PATH:I" set "JPACKAGE_CMD=%%~$PATH:I"
)
if not defined JPACKAGE_CMD if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\jpackage.exe" set "JPACKAGE_CMD=%JAVA_HOME%\bin\jpackage.exe"
if not defined JPACKAGE_CMD (
	echo jpackage not found in PATH or JAVA_HOME. Install a JDK with jpackage support.
	exit /b 1
)

set "APP_JAR="
for %%F in (target\*.jar) do (
	echo %%~nxF | findstr /I /B "original-" >nul
	if errorlevel 1 if not defined APP_JAR set "APP_JAR=%%~nxF"
)

if not defined APP_JAR (
	echo Cannot find packaged Spring Boot jar in target\.
	exit /b 1
)

if exist dist rmdir /s /q dist
mkdir dist

"%JPACKAGE_CMD%" ^
  --type app-image ^
  --name DocPilotMcp ^
  --input target ^
  --dest dist ^
  --main-jar %APP_JAR% ^
  --app-version 2.0.0 ^
  --vendor DocPilot ^
	--java-options "--enable-native-access=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.io=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.math=ALL-UNNAMED" ^
  --java-options "--add-opens=java.xml/com.sun.org.apache.xerces.internal.jaxp=ALL-UNNAMED"

if errorlevel 1 exit /b 1

echo App image created at dist\DocPilotMcp

REM Qdrant bundling removed

:end
