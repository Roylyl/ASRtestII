param([string]$Ndk = $env:ANDROID_NDK_HOME, [string]$CMake = 'cmake', [string]$Ninja = 'ninja')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$build = Join-Path $project 'build\fsmn'
if (-not $Ndk) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $Ndk = Join-Path $sdkRoot 'ndk\29.0.14206865'
}
$toolchain = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path -LiteralPath $toolchain -PathType Leaf)) { throw '找不到Android NDK工具链，请通过-Ndk或ANDROID_NDK_HOME指定NDK目录。' }
$ninjaPath = (Get-Command $Ninja -CommandType Application -ErrorAction Stop).Source
& $CMake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninjaPath" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'FSMN配置失败' }
& $CMake --build $build --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'FSMN编译失败' }
Copy-Item -Force "$build\libfsmn_vad.so" "$project\app\jni\arm64-v8a\libfsmn_vad.so"
