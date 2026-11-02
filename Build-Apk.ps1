[CmdletBinding()]
param(
    [string]$JdkHome,
    [string]$BuildToolsDirectory,
    [string]$AndroidJar,
    [string]$OutputApk,
    [string]$Keystore = (Join-Path $PSScriptRoot 'signing\v4-asr.p12'),
    [string]$KeyAlias = 'v4-asr',
    [switch]$Unsigned,
    [int]$MinApi = 26
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$appDirectory = Join-Path $PSScriptRoot 'app'
$manifest = Join-Path $appDirectory 'AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw "源文件尚未就绪：$manifest" }
if (-not $OutputApk) {
    $manifestXml = [xml][IO.File]::ReadAllText($manifest)
    $version = $manifestXml.DocumentElement.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
    if ($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw "默认安装包命名要求versionName为三段数字，当前为：$version" }
    $suffix = if ($Unsigned) { '-unsigned' } else { '' }
    $name = "ASRtestII-$version$suffix.apk"
    $desktop = [Environment]::GetFolderPath('DesktopDirectory')
    if (-not $desktop) { throw '无法定位Windows桌面，请通过-OutputApk指定输出路径。' }
    $OutputApk = Join-Path $desktop $name
}
if (-not $Unsigned -and -not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    throw "找不到签名密钥：$Keystore。公开源码可用-Unsigned构建未签名APK，或用-Keystore和-KeyAlias指定自己的密钥。覆盖安装需要沿用原证书。"
}
$assetsDirectory = Join-Path $appDirectory 'assets'
$modelFiles = @('models/1920/encoder-1920ms.onnx', 'models/1920/decoder-1920ms.onnx',
    'models/1920/joiner-1920ms.onnx', 'models/1920/bpe.vocab', 'models/1920/tokens.txt',
    'models/campplus.onnx', 'models/eres2netv2.onnx', 'models/pyannote-segmentation.onnx',
    'models/silero_vad.onnx', 'models/ten-vad.onnx', 'fsmn/fsmn-vad.gguf')
foreach ($relativePath in $modelFiles) {
    $file = Get-Item -LiteralPath (Join-Path $assetsDirectory $relativePath) -ErrorAction SilentlyContinue
    if (-not $file -or $file.Length -lt 1024) {
        throw "模型文件缺失、为空或仍是Git LFS指针：$relativePath。请在仓库根目录执行git lfs pull后重试。"
    }
}
$sourceDirectory = Join-Path $appDirectory 'src'
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceDirectory -Filter '*.java' -File -Recurse)
$kotlinFiles = @(Get-ChildItem -LiteralPath $sourceDirectory -Filter '*.kt' -File -Recurse)
if (($sourceFiles.Count + $kotlinFiles.Count) -eq 0) { throw '没有找到Java或Kotlin源码。' }

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $JdkHome) {
    $JdkHome = $env:JAVA_HOME
    if (-not $JdkHome) {
        $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($compiler) {
            $JdkHome = Split-Path (Split-Path $compiler.Source -Parent) -Parent
        } else {
            $jdkRoot = Join-Path $env:LOCALAPPDATA 'RayNeoDev\jdk'
            $portable = Get-ChildItem -LiteralPath $jdkRoot -Filter 'javac.exe' -File -Recurse -ErrorAction SilentlyContinue |
                Sort-Object FullName | Select-Object -First 1
            if (-not $portable) { throw '请安装JDK17并设置JAVA_HOME，或通过-JdkHome指定JDK目录。' }
            $JdkHome = Split-Path $portable.DirectoryName -Parent
        }
    }
}
$java = Join-Path $JdkHome 'bin\java.exe'
$javac = Join-Path $JdkHome 'bin\javac.exe'
foreach ($file in @($java, $javac)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "JDK组件缺失：$file" }
}
if (-not $BuildToolsDirectory) {
    $toolRoot = Join-Path $sdkRoot 'build-tools'
    $aaptCandidates = @(Get-ChildItem -LiteralPath $toolRoot -Filter 'aapt2.exe' -File -Recurse |
        Sort-Object FullName -Descending)
    foreach ($candidate in $aaptCandidates) {
        $candidateRoot = $candidate.DirectoryName
        if ((Test-Path -LiteralPath (Join-Path $candidateRoot 'lib\d8.jar')) -and
            (Test-Path -LiteralPath (Join-Path $candidateRoot 'lib\apksigner.jar')) -and
            (Test-Path -LiteralPath (Join-Path $candidateRoot 'zipalign.exe'))) {
            $BuildToolsDirectory = $candidateRoot
            break
        }
    }
    if (-not $BuildToolsDirectory) { throw "完整build-tools尚未就绪：$toolRoot" }
}
if (-not $AndroidJar) {
    $platformRoot = Join-Path $sdkRoot 'platforms'
    $platformJar = Get-ChildItem -LiteralPath $platformRoot -Filter 'android.jar' -File -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $platformJar) { throw "Android平台包尚未就绪：$platformRoot" }
    $AndroidJar = $platformJar.FullName
}
$aapt2 = Join-Path $BuildToolsDirectory 'aapt2.exe'
$d8 = Join-Path $BuildToolsDirectory 'lib\d8.jar'
$apksigner = Join-Path $BuildToolsDirectory 'lib\apksigner.jar'
$zipalign = Join-Path $BuildToolsDirectory 'zipalign.exe'
foreach ($file in @($AndroidJar, $aapt2, $d8, $apksigner, $zipalign)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "构建组件缺失：$file" }
}

$buildDirectory = Join-Path $PSScriptRoot ('build\run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classesDirectory = Join-Path $buildDirectory 'classes'
$dexDirectory = Join-Path $buildDirectory 'dex'
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
New-Item -ItemType Directory -Path $classesDirectory, $dexDirectory,
    (Split-Path $OutputApk -Parent) -Force | Out-Null
$script:BuildLog = Join-Path $buildDirectory 'build.log'

function Invoke-BuildCommand {
    param([string]$Label, [string]$File, [string[]]$Arguments)
    Write-Host $Label
    Add-Content -LiteralPath $script:BuildLog -Value $Label -Encoding utf8
    $commandOutput = @(& $File @Arguments 2>&1)
    $commandOutput | Out-File -LiteralPath $script:BuildLog -Append -Encoding utf8
    $commandOutput | Select-Object -Last 12 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "$Label 失败，退出码$LASTEXITCODE；日志：$script:BuildLog" }
}

$libraryDirectory = Join-Path $appDirectory 'libs'
$libraries = @()
if (Test-Path -LiteralPath $libraryDirectory -PathType Container) {
    $libraries = @(Get-ChildItem -LiteralPath $libraryDirectory -Filter '*.jar' -File | Sort-Object Name)
}
$classpath = (@($classesDirectory, $AndroidJar) + @($libraries | ForEach-Object { $_.FullName })) -join ';'
$bootClasspath = $AndroidJar
$lambdaStubs = Join-Path $BuildToolsDirectory 'core-lambda-stubs.jar'
if (Test-Path -LiteralPath $lambdaStubs -PathType Leaf) {
    $bootClasspath += ';' + $lambdaStubs
}
if ($kotlinFiles.Count -gt 0) {
    $compilerDirectory = Join-Path $PSScriptRoot 'tools\kotlin'
    $compilerJar = Join-Path $compilerDirectory 'kotlin-compiler-embeddable-2.1.0.jar'
    if (-not (Test-Path -LiteralPath $compilerJar -PathType Leaf)) {
        throw "缺少仓库随附的Kotlin编译器：$compilerJar"
    }
    $compilerClasspath = (@(Get-ChildItem -LiteralPath $compilerDirectory -Filter '*.jar' -File | Sort-Object Name | ForEach-Object FullName)) -join ';'
    $kotlinArgumentsFile = Join-Path $buildDirectory 'kotlin-sources.args'
    $kotlinArguments = @('-no-stdlib', '-no-reflect', '-jvm-target', '1.8',
        '-module-name', 'v4_asr', '-classpath', $classpath, '-d', $classesDirectory)
    # Java sources are resolved by Kotlin but compiled by javac in the next step.
    $kotlinArguments += @(@($kotlinFiles) + @($sourceFiles) | ForEach-Object FullName)
    $escapedKotlinArguments = @($kotlinArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' })
    [IO.File]::WriteAllLines($kotlinArgumentsFile, [string[]]$escapedKotlinArguments, [Text.UTF8Encoding]::new($false))
    Invoke-BuildCommand '编译Kotlin源码' $java @('-Dfile.encoding=UTF-8', '-Xmx2g',
        '-cp', $compilerClasspath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', ('@' + $kotlinArgumentsFile))
}
if ($sourceFiles.Count -gt 0) {
    $sourceList = Join-Path $buildDirectory 'sources.args'
    $quotedSources = @($sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [IO.File]::WriteAllLines($sourceList, [string[]]$quotedSources, [Text.UTF8Encoding]::new($false))
    Invoke-BuildCommand '编译Java源码' $javac @(
        '-J-Duser.language=en', '-J-Duser.country=US',
        '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
        '-bootclasspath', $bootClasspath, '-classpath', $classpath,
        '-d', $classesDirectory, ('@' + $sourceList)
    )
}

$apkDirectory = Join-Path ([IO.Path]::GetTempPath()) ('ASRtestII-' + [Guid]::NewGuid().ToString('N'))
$apkDirectory = [IO.Path]::GetFullPath($apkDirectory)
if ($apkDirectory.StartsWith($PSScriptRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw '系统临时目录位于源码仓库内，请将TEMP设为仓库外的目录后重试。'
}
New-Item -ItemType Directory -Path $apkDirectory | Out-Null
try {
    $baseApk = Join-Path $apkDirectory 'base.apk'
    $aaptArguments = @('link', '-o', $baseApk, '--manifest', $manifest, '-I', $AndroidJar)
    $resourceDirectory = Join-Path $appDirectory 'res'
    if (Test-Path -LiteralPath $resourceDirectory -PathType Container) {
        $compiledResources = Join-Path $buildDirectory 'resources.zip'
        Invoke-BuildCommand '编译Android资源' $aapt2 @('compile', '--dir', $resourceDirectory, '-o', $compiledResources)
        $aaptArguments += @('-R', $compiledResources, '--auto-add-overlay')
    }
    Invoke-BuildCommand '生成APK基础包' $aapt2 $aaptArguments

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $classesJar = Join-Path $buildDirectory 'compiled-classes.jar'
    [IO.Compression.ZipFile]::CreateFromDirectory($classesDirectory, $classesJar)
    $d8Arguments = @('-Xmx1g', '-cp', $d8, 'com.android.tools.r8.D8',
        '--lib', $AndroidJar, '--min-api', $MinApi.ToString(), '--output', $dexDirectory, $classesJar)
    $d8Arguments += @($libraries | ForEach-Object { $_.FullName })
    Invoke-BuildCommand '生成DEX' $java $d8Arguments

    $unsignedApk = Join-Path $apkDirectory 'unsigned.apk'
    $archive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Create)
    try {
        # Stream large model assets to disk rather than buffering an APK update in memory.
        $baseArchive = [IO.Compression.ZipFile]::OpenRead($baseApk)
        try {
            foreach ($entry in $baseArchive.Entries) {
                $compression = if ($entry.CompressedLength -lt $entry.Length) { [IO.Compression.CompressionLevel]::Optimal } else { [IO.Compression.CompressionLevel]::NoCompression }
                $dest = $archive.CreateEntry($entry.FullName, $compression).Open()
                try {
                    $source = $entry.Open()
                    try { $source.CopyTo($dest) } finally { $source.Dispose() }
                } finally { $dest.Dispose() }
            }
        } finally { $baseArchive.Dispose() }
        $dexFiles = @(Get-ChildItem -LiteralPath $dexDirectory -Filter 'classes*.dex' -File)
        if ($dexFiles.Count -eq 0) { throw 'd8没有生成DEX文件。' }
        foreach ($dexFile in $dexFiles) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dexFile.FullName,
                $dexFile.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
        if (Test-Path -LiteralPath $assetsDirectory -PathType Container) {
            foreach ($asset in (Get-ChildItem -LiteralPath $assetsDirectory -File -Recurse)) {
                $relative = $asset.FullName.Substring($assetsDirectory.Length + 1).Replace('\', '/')
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $asset.FullName,
                    ('assets/' + $relative), [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
            }
        }
        $nativeDirectory = Join-Path $appDirectory 'jni\arm64-v8a'
        if (Test-Path -LiteralPath $nativeDirectory -PathType Container) {
            foreach ($library in (Get-ChildItem -LiteralPath $nativeDirectory -Filter '*.so' -File)) {
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $library.FullName,
                    ('lib/arm64-v8a/' + $library.Name), [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
            }
        }
    }
    finally { $archive.Dispose() }

    $alignedApk = Join-Path $apkDirectory 'aligned.apk'
    Invoke-BuildCommand '对齐APK' $zipalign @('-p', '-f', '32', $unsignedApk, $alignedApk)
    $finalApk = $alignedApk
    if (-not $Unsigned) {
        $finalApk = Join-Path $apkDirectory 'signed.apk'
        & (Join-Path $PSScriptRoot 'tools\Sign-Apk.ps1') -InputApk $alignedApk -OutputApk $finalApk -Java $java -Apksigner $apksigner -Keystore $Keystore -KeyAlias $KeyAlias
        Invoke-BuildCommand '验证APK签名' $java @('-jar', $apksigner, 'verify', '--verbose', $finalApk)
    }

    Copy-Item -LiteralPath $finalApk -Destination $OutputApk -Force
} finally {
    Get-ChildItem -LiteralPath $apkDirectory -File -Force | Remove-Item -Force
    [IO.Directory]::Delete($apkDirectory)
}
$metadata = [ordered]@{
    builtAt = (Get-Date -Format 'o')
    apk = $OutputApk
    apkBytes = (Get-Item -LiteralPath $OutputApk).Length
    jdk = $JdkHome
    buildTools = $BuildToolsDirectory
    androidJar = $AndroidJar
    minApiForDex = $MinApi
    javaSources = $sourceFiles.Count
    kotlinSources = $kotlinFiles.Count
    kotlinCompiler = '2.1.0 standalone embeddable compiler'
    javaLibraries = @($libraries | ForEach-Object { $_.Name })
    signed = (-not $Unsigned)
    keystore = if ($Unsigned) { $null } else { $Keystore }
    log = $script:BuildLog
    deviceDeployment = 'Not performed by this script'
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $buildDirectory 'build-result.json') -Encoding utf8
Write-Host "APK构建完成：$OutputApk"
Write-Host "构建日志：$script:BuildLog"
