param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [string]$SdkPath = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\update-release')
)

$ErrorActionPreference = 'Stop'
$sourceApk = (Resolve-Path -LiteralPath $ApkPath).Path
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
    Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat'))
    } |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw 'No se encontraron las herramientas de compilación de Android.' }

$badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $sourceApk
if ($LASTEXITCODE -ne 0) { throw 'No se pudo leer el APK.' }
$packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($packageLine -notmatch "name='com\.quirogaj\.vistio'") { throw 'El APK debe pertenecer a Vistio.' }
if ($packageLine -notmatch "versionCode='(\d+)'") { throw 'El APK no tiene versionCode.' }
$code = [long]$Matches[1]
if ($packageLine -notmatch "versionName='([^']+)'") { throw 'El APK no tiene versionName.' }
$name = $Matches[1]
if ($name -notmatch '^(0|[1-9]\d{0,5})\.(0|[1-9]\d?)\.(0|[1-9]\d?)$') {
    throw 'La versión debe usar MAYOR.MENOR.PARCHE.'
}
$expectedCode = [long]$Matches[1] * 10000L + [long]$Matches[2] * 100L + [long]$Matches[3]
if ($expectedCode -ne $code) { throw 'El código interno no corresponde a la versión del APK.' }
& (Join-Path $buildTools.FullName 'apksigner.bat') verify $sourceApk
if ($LASTEXITCODE -ne 0) { throw 'El APK no tiene una firma válida.' }

$outputFolder = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputFolder -Force | Out-Null
$assetName = "vistio-$name.apk"
$destinationApk = Join-Path $outputFolder $assetName
if ($sourceApk -ne $destinationApk) {
    Copy-Item -LiteralPath $sourceApk -Destination $destinationApk -Force
}
$metadata = [ordered]@{
    applicationId = 'com.quirogaj.vistio'
    versionCode = $code
    versionName = $name
    apkAsset = $assetName
    sha256 = (Get-FileHash -LiteralPath $destinationApk -Algorithm SHA256).Hash.ToLowerInvariant()
}
$metadataPath = Join-Path $outputFolder 'update.json'
[IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
"APK: $destinationApk"
"Metadatos: $metadataPath"
