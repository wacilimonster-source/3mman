@echo off
chcp 65001 >nul
echo ========================================
echo      3mman APK Build Script
echo ========================================
echo.

:: Setup JDK
set JAVA_HOME=C:\jdk17\jdk-17.0.14+7
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo [1/3] Downloading JDK 17...
    powershell -Command "Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/version/jdk-17.0.14+7/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' -OutFile '%TEMP%\jdk17.zip' -UseBasicParsing"
    powershell -Command "Expand-Archive -Path '%TEMP%\jdk17.zip' -DestinationPath 'C:\jdk17' -Force"
    echo JDK 17 installed.
) else (
    echo [1/3] JDK 17 ready.
)

:: Setup Gradle
if not exist "C:\gradle-7.6.4\bin\gradle.bat" (
    echo [2/3] Downloading Gradle 7.6.4...
    powershell -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-7.6.4-bin.zip' -OutFile '%TEMP%\gradle-7.6.4.zip' -UseBasicParsing"
    powershell -Command "Expand-Archive -Path '%TEMP%\gradle-7.6.4.zip' -DestinationPath 'C:\' -Force"
    echo Gradle 7.6.4 installed.
) else (
    echo [2/3] Gradle 7.6.4 ready.
)

:: Kill any existing Java processes
echo [2/3] Cleaning up...
taskkill /F /IM java.exe 2>nul
timeout /t 2 /nobreak >nul

:: Pre-create Gradle cache directories
if not exist "C:\grd_cache\notifications\7.6.4" mkdir "C:\grd_cache\notifications\7.6.4"
if not exist "C:\grd_cache\caches\7.6.4" mkdir "C:\grd_cache\caches\7.6.4"
if not exist "C:\grd_cache\daemon\7.6.4" mkdir "C:\grd_cache\daemon\7.6.4"
if not exist "C:\grd_cache\wrapper\dists" mkdir "C:\grd_cache\wrapper\dists"

:: Set environment variables
set JAVA_TOOL_OPTIONS=-Dorg.gradle.native=false
set GRADLE_USER_HOME=C:\grd_cache

:: Build APK
echo.
echo [3/3] Building Debug APK (this may take 5-15 minutes)...
echo.
call gradlew.bat --no-daemon assembleDebug

:: Check result
echo.
if exist app\build\outputs\apk\debug\*.apk (
    echo ========================================
    echo  BUILD SUCCESSFUL!
    echo ========================================
    for %%f in (app\build\outputs\apk\debug\*.apk) do (
        echo  APK: %%f
        call :filesize "%%f"
    )
) else (
    echo Build failed. Check errors above.
)

echo.
pause
goto :eof

:filesize
set SIZEFILE=%~1
set SIZEBYTES=%~z1
set /a SIZEMB=%SIZEBYTES% / 1048576
echo  Size: %SIZEMB% MB
goto :eof