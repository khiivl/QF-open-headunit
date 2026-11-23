<#
  Builds the CoD OS placeholder of Open Headunit and packs the Magisk module.

    tools\build_codos.ps1            build + sign + verify + module zip   (output: dist\)
    tools\build_codos.ps1 -SkipBuild only re-pack the module from the APK already staged

  The CoD platform key stays outside the repository. The passwords are read from the owner's
  README into environment variables of this process only: they are never printed, logged, written
  to a file or passed on a command line (apksigner reads them from the environment through
  `env:`). Gradle reads CODOS_KEYSTORE_FILE / CODOS_KEY_ALIAS / CODOS_KEYSTORE_PASSWORD /
  CODOS_KEY_PASSWORD (see app\build.gradle.kts).
#>
param(
    [switch]$SkipBuild,
    [string]$Readme   = 'C:\Users\kosty\Community_of_Devs\README_UKR.txt',
    [string]$Keystore = 'C:\Users\kosty\Community_of_Devs\keystore\community_of_devs.jks',
    [string]$Alias    = 'key0'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Read-ReadmeValue([string]$name) {
    $line = Get-Content -LiteralPath $Readme -Encoding UTF8 | Where-Object { $_ -match "^$([regex]::Escape($name))=" } | Select-Object -First 1
    if (-not $line) { throw "no '$name=' line in the README" }
    return ($line.Substring($name.Length + 1)).Trim()
}

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_HOME = $sdk
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.]','') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apksigner) { throw 'apksigner not found in the Android SDK build-tools' }

$env:CODOS_KEYSTORE_FILE     = $Keystore
$env:CODOS_KEY_ALIAS         = $Alias
$env:CODOS_KEYSTORE_PASSWORD = Read-ReadmeValue 'keystore.password'
try { $env:CODOS_KEY_PASSWORD = Read-ReadmeValue 'key.password' } catch { $env:CODOS_KEY_PASSWORD = $env:CODOS_KEYSTORE_PASSWORD }

$name = 'OpenHeadunit.apk'
$stage = Join-Path $root 'magisk\system\priv-app\OpenHeadunit'
$apk = Join-Path $stage $name

if (-not $SkipBuild) {
    & "$root\gradlew.bat" ':app:assembleCodosRelease'
    if ($LASTEXITCODE -ne 0) { throw 'gradle assembleCodosRelease failed' }
    $built = Get-ChildItem "$root\app\build\outputs\apk\codos\release" -Filter '*.apk' | Select-Object -First 1
    if (-not $built) { throw 'no codos release APK was produced' }
    New-Item -ItemType Directory -Force $stage | Out-Null
    Copy-Item -LiteralPath $built.FullName -Destination $apk -Force
}
if (-not (Test-Path $apk)) { throw "no staged APK at $apk" }

# Verify the signature and print the signer digest (the certificate is public; no secret is printed).
$certs = & $apksigner verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }
$digest = ($certs | Where-Object { $_ -match 'certificate SHA-256 digest' } | Select-Object -First 1) -replace '.*digest:\s*',''
Write-Host "signer SHA-256: $digest"
if (-not ($digest.StartsWith('766232df') -and $digest.EndsWith('ad9e'))) { throw "unexpected signer $digest (want 766232df...ad9e)" }

# Pack the module: LF text, forward-slash entry names, files at the zip root.
$mod = Join-Path $root 'magisk'
Get-ChildItem $mod -Recurse -File | Where-Object { $_.Extension -in '.sh','.prop','.xml' -or $_.Name -in 'update-binary','updater-script' } | ForEach-Object {
    $text = [IO.File]::ReadAllText($_.FullName)
    $lf = $text -replace "`r`n","`n"
    if ($lf -ne $text) { [IO.File]::WriteAllText($_.FullName, $lf, (New-Object Text.UTF8Encoding $false)) }
}
$prop = @{}; Get-Content (Join-Path $mod 'module.prop') | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $prop[$Matches[1]] = $Matches[2] } }
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$zip = Join-Path $dist "OpenHeadunit_codos_$($prop['version']).zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$za = [IO.Compression.ZipFile]::Open($zip, 'Create')
try {
    Get-ChildItem $mod -Recurse -File | Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($mod.Length + 1).Replace('\','/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($za, $_.FullName, $rel, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $za.Dispose() }

Write-Host "APK : $apk"
Write-Host ("APK sha256 : " + (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower())
Write-Host "ZIP : $zip"
Write-Host ("ZIP sha256 : " + (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower())
