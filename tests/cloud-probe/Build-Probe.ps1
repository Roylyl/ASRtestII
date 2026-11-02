[CmdletBinding()]
param(
    [string]$TargetBuild,
    [string]$Keystore = (Join-Path $PSScriptRoot '..\..\signing\v4-asr.p12'),
    [string]$KeyAlias = 'v4-asr'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$stdlib = (Get-ChildItem "$root\app\libs" -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1).FullName
if (-not $TargetBuild) {
    $TargetBuild = (Get-ChildItem "$root\build" -Directory | Where-Object {
        (Test-Path (Join-Path $_.FullName 'classes\org\hotwordtest\CloudChatClient.class')) -and
        (Test-Path (Join-Path $_.FullName 'build-result.json'))
    } | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
$metadata = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $TargetBuild 'build-result.json') | ConvertFrom-Json
$jdk = Join-Path $metadata.jdk 'bin'
$bt = $metadata.buildTools
$android = $metadata.androidJar
$targetJar = Join-Path $TargetBuild 'compiled-classes.jar'
if (-not (Test-Path $targetJar)) { throw '请提供含正式CloudChatClient和TranscriptReview的构建目录' }
$build = New-Item -ItemType Directory (Join-Path $env:TEMP ("hotword-cloud-probe-" + [Guid]::NewGuid().ToString("N")))
$classes = New-Item -ItemType Directory "$build\classes"
$dex = New-Item -ItemType Directory "$build\dex"
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}
Run "$jdk\javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-bootclasspath',"$android;$bt\core-lambda-stubs.jar",'-classpath',"$targetJar;$stdlib",'-d',$classes.FullName,"$PSScriptRoot\CloudProbe.java")
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$probeJar = "$build\probe.jar"
[IO.Compression.ZipFile]::CreateFromDirectory($classes,$probeJar)
Run "$jdk\java.exe" @('-cp',"$bt\lib\d8.jar",'com.android.tools.r8.D8','--min-api','26','--lib',$android,'--classpath',$targetJar,'--classpath',$stdlib,'--output',$dex.FullName,$probeJar)
$plain = "$build\plain.apk"
Run "$bt\aapt2.exe" @('link','--manifest',"$PSScriptRoot\AndroidManifest.xml",'-I',$android,'-o',$plain)
$archive = [IO.Compression.ZipFile]::Open($plain,[IO.Compression.ZipArchiveMode]::Update)
try {
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,"$dex\classes.dex",'classes.dex',[IO.Compression.CompressionLevel]::Optimal)
} finally { $archive.Dispose() }
$aligned = "$build\aligned.apk"
Run "$bt\zipalign.exe" @('-f','4',$plain,$aligned)
$output = "$PSScriptRoot\cloud-probe.apk"
& (Join-Path $root 'tools\Sign-Apk.ps1') -InputApk $aligned -OutputApk $output -Java "$jdk\java.exe" -Apksigner "$bt\lib\apksigner.jar" -Keystore $Keystore -KeyAlias $KeyAlias
Write-Host $output
