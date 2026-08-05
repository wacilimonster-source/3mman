@echo off
chcp 65001 >nul
echo ========================================
echo      3mman APK 构建脚本
echo ========================================
echo.

:: 设置 JDK 17 路径
set JAVA_HOME=C:\jdk17\jdk-17.0.14+7
echo [1/4] 检查 JDK...
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo JDK 未安装，正在下载...
    powershell -Command "Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/version/jdk-17.0.14+7/windows/x64/jdk/hotspot/normal/eclipse?project=jdk' -OutFile '%TEMP%\jdk17.zip' -UseBasicParsing"
    powershell -Command "Expand-Archive -Path '%TEMP%\jdk17.zip' -DestinationPath 'C:\jdk17' -Force"
    echo JDK 下载并解压完成
) else (
    echo JDK 17 已就绪
)

"%JAVA_HOME%\bin\javac" -version

:: 切换到项目目录
cd /d "g:\game\新建文件夹\3mman"

echo.
echo [2/4] 清理构建缓存...
:: 杀掉所有可能锁住 Gradle 缓存的 Java 进程
taskkill /F /IM java.exe 2>nul
timeout /t 2 /nobreak >nul

:: 设置全新的 Gradle 缓存目录（避免锁文件冲突）
set GRADLE_USER_HOME=%TEMP%\gradle_home_3mman_%RANDOM%
echo 使用临时缓存目录: %GRADLE_USER_HOME%

echo.
echo [3/4] 开始构建 Debug APK（耗时较长，请耐心等待）...
echo.
call gradlew.bat assembleDebug --no-daemon

echo.
echo [4/4] 构建完成
if exist app\build\outputs\apk\debug\ (
    echo.
    echo APK 文件位置:
    dir /b app\build\outputs\apk\debug\*.apk 2>nul
) else (
    echo APK 未找到，请检查上方错误信息
)

echo.
pause