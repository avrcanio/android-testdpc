@echo off
cd C:\Users\avrca\Projekti\testDPC.qubit.mdm\android-testdpc
set BAZEL_SH=C:\Program Files\Git\bin\bash.exe
.\.bazelisk\bazel.exe shutdown
.\.bazelisk\bazel.exe build //:testdpc --define variant=lite

REM --- Sign output APK for Play upload (lite variant) ---
REM Requirements:
REM - Android SDK build-tools (zipalign + apksigner)
REM - Upload keystore at .\signing\upload.keystore (alias: upload)
REM - Set passwords via env var (avoid committing secrets):
REM     set UPLOAD_KEYSTORE_PASS=...
REM     set UPLOAD_KEY_PASS=...

REM NOTE: Hardcoded defaults (requested). Consider removing before sharing the repo.
if "%UPLOAD_KEYSTORE_PASS%"=="" set "UPLOAD_KEYSTORE_PASS=V7p!fN2qZr#8kLc0"
if "%UPLOAD_KEY_PASS%"=="" set "UPLOAD_KEY_PASS=V7p!fN2qZr#8kLc0"

if "%UPLOAD_KEYSTORE_PASS%"=="" (
  echo ERROR: Missing UPLOAD_KEYSTORE_PASS environment variable.
  exit /b 1
)
if "%UPLOAD_KEY_PASS%"=="" (
  echo ERROR: Missing UPLOAD_KEY_PASS environment variable.
  exit /b 1
)

set "BT_ROOT=%LOCALAPPDATA%\Android\Sdk\build-tools"
if not exist "%BT_ROOT%" (
  echo ERROR: Android SDK build-tools not found at "%BT_ROOT%".
  exit /b 1
)

set "BUILD_TOOLS="
for /f "delims=" %%i in ('dir /b /ad "%BT_ROOT%" ^| sort /r') do (
  set "BUILD_TOOLS=%%i"
  goto :found_build_tools
)
:found_build_tools
if "%BUILD_TOOLS%"=="" (
  echo ERROR: No build-tools versions found under "%BT_ROOT%".
  exit /b 1
)

set "ZIPALIGN=%BT_ROOT%\%BUILD_TOOLS%\zipalign.exe"
set "APKSIGNER=%BT_ROOT%\%BUILD_TOOLS%\apksigner.bat"

if not exist "%ZIPALIGN%" (
  echo ERROR: zipalign not found: "%ZIPALIGN%"
  exit /b 1
)
if not exist "%APKSIGNER%" (
  echo ERROR: apksigner not found: "%APKSIGNER%"
  exit /b 1
)

if not exist "bazel-bin\testdpc.apk" (
  echo ERROR: Expected output not found: bazel-bin\testdpc.apk
  exit /b 1
)

"%ZIPALIGN%" -p 4 "bazel-bin\testdpc.apk" "bazel-bin\testdpc-lite-aligned.apk"

"%APKSIGNER%" sign ^
  --ks "signing\upload.keystore" ^
  --ks-key-alias "upload" ^
  --ks-pass "pass:%UPLOAD_KEYSTORE_PASS%" ^
  --key-pass "pass:%UPLOAD_KEY_PASS%" ^
  --v3-signing-enabled true ^
  --v4-signing-enabled true ^
  --out "bazel-bin\testdpc-lite-release.apk" ^
  "bazel-bin\testdpc-lite-aligned.apk"

"%APKSIGNER%" verify --verbose "bazel-bin\testdpc-lite-release.apk"
echo OK: Signed lite APK at bazel-bin\testdpc-lite-release.apk
