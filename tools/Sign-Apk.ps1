[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$Apksigner,
    [string]$Keystore = (Join-Path $PSScriptRoot '..\signing\v4-asr.p12'),
    [string]$KeyAlias = 'v4-asr'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    throw "找不到签名密钥：$Keystore"
}
$oldStorePassword = $env:ASRTESTII_STORE_PASSWORD
$securePassword = $null
try {
    if (-not $oldStorePassword) {
        $defaultKey = Join-Path $PSScriptRoot '..\signing\v4-asr.p12'
        $localPassword = Join-Path $PSScriptRoot '..\signing\release-password.dpapi'
        if ([IO.Path]::GetFullPath($Keystore) -eq [IO.Path]::GetFullPath($defaultKey) -and
            (Test-Path -LiteralPath $localPassword -PathType Leaf)) {
            $securePassword = Get-Content -LiteralPath $localPassword | ConvertTo-SecureString
        } else {
            $securePassword = Read-Host "输入密钥库口令（$KeyAlias）" -AsSecureString
        }
        $env:ASRTESTII_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
    }
    $keyVariable = if ($env:ASRTESTII_KEY_PASSWORD) { 'ASRTESTII_KEY_PASSWORD' } else { 'ASRTESTII_STORE_PASSWORD' }
    & $Java -jar $Apksigner sign --v4-signing-enabled false --ks $Keystore --ks-key-alias $KeyAlias --ks-pass env:ASRTESTII_STORE_PASSWORD --key-pass "env:$keyVariable" --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw "APK签名失败，退出码$LASTEXITCODE" }
} finally {
    $env:ASRTESTII_STORE_PASSWORD = $oldStorePassword
    if ($null -ne $securePassword) { $securePassword.Dispose() }
}
