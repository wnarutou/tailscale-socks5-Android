$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$utf8 = [System.Text.Encoding]::UTF8

function Read-Utf8([string]$relativePath) {
    return [System.IO.File]::ReadAllText((Join-Path $repoRoot $relativePath), $utf8)
}

$failures = [System.Collections.Generic.List[string]]::new()

function Assert-Matches([string]$content, [string]$pattern, [string]$message) {
    if ($content -notmatch $pattern) {
        $failures.Add($message)
    }
}

function Assert-NotMatches([string]$content, [string]$pattern, [string]$message) {
    if ($content -match $pattern) {
        $failures.Add($message)
    }
}

$manifest = Read-Utf8 "android-app/app/src/main/AndroidManifest.xml"
$service = Read-Utf8 "android-app/app/src/main/java/com/tsproxy/android/service/TsProxyService.kt"
$mobile = Read-Utf8 "mobile/tsproxy.go"
$readme = Read-Utf8 "README.md"
$appBuild = Read-Utf8 "android-app/app/build.gradle.kts"

Assert-Matches $manifest 'android\.permission\.WAKE_LOCK' `
    "AndroidManifest.xml must declare android.permission.WAKE_LOCK"
Assert-Matches $service '(?s)newWakeLock\(.*?\)\.apply\s*\{.*?acquire\(\s*\)' `
    "TsProxyService must hold the WakeLock for the service lifetime"
Assert-Matches $service 'wakeLock\?\.isHeld\s*==\s*true' `
    "TsProxyService must avoid acquiring a duplicate WakeLock"
Assert-Matches $service 'setReferenceCounted\(false\)' `
    "TsProxyService WakeLock must not be reference counted"
Assert-NotMatches $service 'acquire\([^)]*[0-9][^)]*\)' `
    "TsProxyService must not use a fixed WakeLock timeout"
Assert-Matches $service '(?s)private fun stopProxy\(\).*?finally\s*\{.*?releaseWakeLock\(\)' `
    "Stopping the proxy must release the WakeLock"
Assert-Matches $service '(?s)override fun onDestroy\(\).*?releaseWakeLock\(\)' `
    "Destroying the service must release the WakeLock"
Assert-Matches $service 'return\s+START_REDELIVER_INTENT' `
    "Android must redeliver the start Intent when recreating the service"
Assert-Matches $mobile 'mobileTCPTimeout\s*=\s*0' `
    "Mobile TCP idle timeout must be disabled"
Assert-Matches $mobile 'mobileUDPTimeout\s*=\s*0' `
    "Mobile UDP idle timeout must be disabled"
Assert-Matches $mobile 'NewTsProxy\(srv,\s*mobileTCPTimeout,\s*mobileUDPTimeout,\s*mobileSocksDebug\)' `
    "Mobile proxy must use the named mobile SOCKS settings"
Assert-Matches $mobile 'SOCKS settings: TCP timeout=%d UDP timeout=%d debug=%t \(0=disabled\)' `
    "Startup logs must format the actual SOCKS settings"
Assert-Matches $readme 'com\.tsproxy\.android' `
    "README must identify the Android package to exclude from Clash VPN"
Assert-Matches $readme 'ts-proxy App [^\r\n]+ Clash VPN' `
    "README must document bypassing Clash VPN for ts-proxy"
Assert-Matches $appBuild 'versionCode\s*=\s*10203' `
    "Android versionCode must be 10203 for the 1.102.3 test release"
Assert-Matches $appBuild 'versionName\s*=\s*"1\.102\.3"' `
    "Android versionName must be 1.102.3 for the test release"

$workflow = Read-Utf8 ".github/workflows/android.yml"
Assert-Matches $workflow 'first-round-contract\.ps1' `
    "CI must run the first-round contract checks"

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    throw "$($failures.Count) first-round contract check(s) failed"
}

Write-Output "All first-round contract checks passed."
