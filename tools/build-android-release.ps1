param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$profilePath = Join-Path $env:LOCALAPPDATA 'SALOM\release-signing.dpapi'
if (-not (Test-Path -LiteralPath $profilePath -PathType Leaf)) { throw 'Protected SALOM signing profile was not found.' }

$protected = [IO.File]::ReadAllBytes($profilePath)
$plain = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
$config = [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json
$env:SALOM_RELEASE_STORE_FILE = $config.storeFile
$env:SALOM_RELEASE_STORE_PASSWORD = $config.storePassword
$env:SALOM_RELEASE_KEY_ALIAS = $config.keyAlias
$env:SALOM_RELEASE_KEY_PASSWORD = $config.keyPassword

try {
    Push-Location (Join-Path $repo 'android')
    try {
        if (-not $SkipTests) {
            & .\gradlew.bat :app:testDebugUnitTest --no-daemon
            if ($LASTEXITCODE -ne 0) { throw "Android unit tests failed ($LASTEXITCODE)." }
        }
        & .\gradlew.bat :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Android release build failed ($LASTEXITCODE)." }
        $apk = Join-Path $repo 'android\app\build\outputs\apk\release\app-release.apk'
        if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Gradle did not produce the release APK.' }
        $sdk = $env:ANDROID_SDK_ROOT
        if (-not $sdk) { $sdk = $env:ANDROID_HOME }
        if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        $signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter apksigner.bat -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
        if (-not $signer) { throw 'Android SDK apksigner was not found.' }
        & $signer.FullName verify --verbose --print-certs $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
        Write-Output "Signed APK: $apk"
    }
    finally { Pop-Location }
}
finally {
    Remove-Item Env:SALOM_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:SALOM_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:SALOM_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:SALOM_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    [Array]::Clear($plain, 0, $plain.Length)
    [Array]::Clear($protected, 0, $protected.Length)
}
