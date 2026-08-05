$script:ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "      3mman APK Build Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ===== Step 1: Setup JDK 17 =====
Write-Host "[1/4] Checking JDK 17..." -ForegroundColor Yellow
$jdkHome = "C:\jdk17\jdk-17.0.14+7"
if (-not (Test-Path "$jdkHome\bin\javac.exe")) {
    Write-Host "Downloading JDK 17 (Temurin)..."
    $url = "https://api.adoptium.net/v3/binary/version/jdk-17.0.14+7/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
    $zipFile = "$env:TEMP\jdk17.zip"
    Invoke-WebRequest -Uri $url -OutFile $zipFile -UseBasicParsing
    Expand-Archive -Path $zipFile -DestinationPath "C:\jdk17" -Force
    Write-Host "JDK 17 installed" -ForegroundColor Green
}
$env:JAVA_HOME = $jdkHome
$javacVer = & "$jdkHome\bin\javac" -version 2>&1
Write-Host "  $javacVer" -ForegroundColor Gray

# ===== Step 2: Setup Gradle 7.6.4 =====
Write-Host ""
Write-Host "[2/4] Checking Gradle 7.6.4..." -ForegroundColor Yellow
$gradleHome = "C:\gradle-7.6.4"
if (-not (Test-Path "$gradleHome\bin\gradle.bat")) {
    Write-Host "Downloading Gradle 7.6.4..."
    $zipFile = "$env:TEMP\gradle-7.6.4.zip"
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-7.6.4-bin.zip" -OutFile $zipFile -UseBasicParsing
    Expand-Archive -Path $zipFile -DestinationPath "C:\" -Force
    Write-Host "Gradle 7.6.4 installed" -ForegroundColor Green
}
Write-Host "  Gradle OK" -ForegroundColor Gray

# ===== Step 3: Build APK =====
Write-Host ""
Write-Host "[3/4] Building Debug APK..." -ForegroundColor Yellow
Write-Host "  (This may take 5-15 minutes, please wait...)" -ForegroundColor Gray

# Kill existing Java processes
Get-Process -Name "java" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Set environment
$env:JAVA_TOOL_OPTIONS = "-Dorg.gradle.native=false"
$env:GRADLE_USER_HOME = "C:\grd_cache"

# Pre-create necessary directories
$dirs = @(
    "C:\grd_cache\notifications\7.6.4",
    "C:\grd_cache\caches\7.6.4",
    "C:\grd_cache\daemon\7.6.4",
    "C:\grd_cache\wrapper\dists",
    "C:\grd_cache\file-changes",
    "C:\grd_cache\file-watching",
    "C:\grd_cache\build-cache",
    "C:\grd_cache\journal"
)
foreach ($d in $dirs) {
    $null = New-Item -ItemType Directory -Path $d -Force -ErrorAction SilentlyContinue
}

# Build classpath
$libDir = "$gradleHome\lib"
$cp = (Get-ChildItem "$libDir\*.jar" | ForEach-Object { $_.FullName }) -join ";"

# Navigate to project
Set-Location "g:\game\新建文件夹\3mman"

# Run Gradle
& "$env:JAVA_HOME\bin\java" -cp "$cp" org.gradle.launcher.GradleMain --no-daemon assembleDebug 2>&1

# ===== Step 4: Check Result =====
Write-Host ""
Write-Host "[4/4] Checking build result..." -ForegroundColor Yellow
$apkPath = "g:\game\新建文件夹\3mman\app\build\outputs\apk\debug"
if (Test-Path $apkPath) {
    $apkFiles = Get-ChildItem $apkPath -Filter "*.apk"
    if ($apkFiles.Count -gt 0) {
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Green
        Write-Host "  BUILD SUCCESSFUL!" -ForegroundColor Green
        Write-Host "========================================" -ForegroundColor Green
        foreach ($apk in $apkFiles) {
            $sizeMB = [math]::Round($apk.Length / 1MB, 2)
            Write-Host "  APK: $($apk.FullName)" -ForegroundColor Green
            Write-Host "  Size: ${sizeMB} MB" -ForegroundColor Gray
        }
    } else {
        Write-Host "  No APK found. Check errors above." -ForegroundColor Red
    }
} else {
    Write-Host "  Build failed. Check errors above." -ForegroundColor Red
}

Write-Host ""
Write-Host "Press Enter to exit..." -ForegroundColor Gray
$null = Read-Host