param([string]$GoogleOAuthJsonPath, [string]$OAuthConfigPath, [string]$AppPublishPath,
      [string]$InstallerName = 'SALOM-0.15.5-Setup.exe')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$appProject = Join-Path $PSScriptRoot 'Salom.Desktop\Salom.Desktop.csproj'
$defaultPublish = Join-Path $PSScriptRoot 'Salom.Desktop\artifacts\publish'
$appPublish = if ($AppPublishPath) { $AppPublishPath } else { $defaultPublish }
$setupProject = Join-Path $PSScriptRoot 'Salom.Setup\Salom.Setup.csproj'
$payloadDir = Join-Path $PSScriptRoot 'Salom.Setup\payload'
$payload = Join-Path $payloadDir 'SalomDesktop.zip'
if ($InstallerName -notmatch '^SALOM-[a-zA-Z0-9.-]+\.exe$') { throw 'Invalid SALOM installer name.' }
$installerOut = Join-Path (Join-Path $repo 'outputs') $InstallerName
New-Item -ItemType Directory -Force -Path $payloadDir,(Split-Path -Parent $installerOut) | Out-Null
$existingConfig = if ($OAuthConfigPath) { $OAuthConfigPath } else { Join-Path $defaultPublish 'salom.oauth.json' }
$desktopOAuth = $null
if ($GoogleOAuthJsonPath -and (Test-Path -LiteralPath $GoogleOAuthJsonPath)) {
    $googleOAuth = Get-Content -LiteralPath $GoogleOAuthJsonPath -Raw | ConvertFrom-Json
    $desktopOAuth = $googleOAuth.installed
    if (-not $desktopOAuth.client_id -or -not $desktopOAuth.client_secret) { throw 'The supplied file is not a Google Desktop OAuth client JSON.' }
} elseif (-not (Test-Path -LiteralPath $existingConfig)) { throw 'Existing local Desktop OAuth configuration is missing. Provide -GoogleOAuthJsonPath or -OAuthConfigPath.' }
$firebasePath = Join-Path $repo 'android\app\google-services.json'
$firebase = Get-Content -LiteralPath $firebasePath -Raw | ConvertFrom-Json
$firebaseClient = $firebase.client | Where-Object { $_.client_info.android_client_info.package_name -eq 'com.mukimov.salom' } | Select-Object -First 1
$firebaseKey = $firebaseClient.api_key[0].current_key
if (-not $firebase.project_info.project_id -or -not $firebaseKey) { throw 'SALOM Firebase project/API key configuration is missing.' }
$oauthConfigPath = Join-Path ([IO.Path]::GetTempPath()) ('salom.oauth.' + [guid]::NewGuid().ToString('N') + '.json')
$oauthConfig = if ($desktopOAuth) { [ordered]@{ googleClientId=$desktopOAuth.client_id; googleClientSecret=$desktopOAuth.client_secret; firebaseWebApiKey=$firebaseKey } }
else { Get-Content -LiteralPath $existingConfig -Raw | ConvertFrom-Json }
if (-not $oauthConfig.googleClientId -or -not $oauthConfig.firebaseWebApiKey) { throw 'The existing Desktop OAuth configuration is incomplete.' }
$oauthConfig.firebaseWebApiKey = $firebaseKey
[IO.File]::WriteAllText($oauthConfigPath, ($oauthConfig | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
Write-Output "Using existing Google Desktop OAuth client and Firebase project $($firebase.project_info.project_id)."
try {
dotnet publish $appProject -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true "-p:SalomOAuthConfigPath=$oauthConfigPath" -o $appPublish
if ($LASTEXITCODE -ne 0) { throw 'Desktop application publish failed.' }
New-Item -ItemType Directory -Force -Path (Join-Path $appPublish 'assets') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets\salom.ico') -Destination (Join-Path $appPublish 'assets\salom.ico') -Force
}
finally { Remove-Item -LiteralPath $oauthConfigPath -Force -ErrorAction SilentlyContinue }
if (Test-Path -LiteralPath $payload) { Remove-Item -LiteralPath $payload -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::Open($payload,[IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $appPublish -File -Recurse | Where-Object { $_.Extension -notin '.pdb','.xml' } | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($appPublish,$_.FullName).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
}
finally { $archive.Dispose() }
dotnet publish $setupProject -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o (Join-Path $PSScriptRoot 'Salom.Setup\artifacts')
if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
$built = Join-Path $PSScriptRoot 'Salom.Setup\artifacts\Salom.Setup.exe'
if (-not (Test-Path -LiteralPath $built)) { throw 'Installer executable is missing.' }
Copy-Item -LiteralPath $built -Destination $installerOut -Force
Write-Output "Installer: $installerOut"
