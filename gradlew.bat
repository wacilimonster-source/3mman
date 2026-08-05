@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows (direct mode - bypass wrapper)
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set DIRNAME=%DIRNAME:~0,-1%

set APP_HOME=%DIRNAME%

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments
set CMD_LINE_ARGS=%*

:execute
@rem Build classpath from all jars in Gradle lib directory
set GRADLE_LIB=C:\gradle-7.6.4\lib
set CLASSPATH=
for %%i in ("%GRADLE_LIB%\*.jar") do call :addcp "%%i"
goto :run

:addcp
set CLASSPATH=%CLASSPATH%;%1
goto :eof

:run
@rem Execute Gradle directly (bypass wrapper to avoid sandbox lock issue)
"%JAVA_EXE%" -cp "%CLASSPATH%" org.gradle.launcher.GradleMain -p "%APP_HOME%" %CMD_LINE_ARGS%
goto end

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
if  not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega