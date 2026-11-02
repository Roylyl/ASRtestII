param([string]$Ndk, [string]$CMake = 'cmake', [string]$Ninja = 'ninja')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$build = Join-Path $project 'build\fsmn'
if (-not $Ndk) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $Ndk = Join-Path $sdkRoot 'ndk\29.0.14206865'
}
& $CMake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'FSMN配置失败' }
& $CMake --build $build --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'FSMN编译失败' }
Copy-Item -Force "$build\libfsmn_vad.so" "$project\app\jni\arm64-v8a\libfsmn_vad.so"
