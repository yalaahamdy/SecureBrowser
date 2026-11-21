param(
    [string]$TagName = "v1.16.0",
    [string]$ApkPath = "C:\Users\Rocket\Documents\antigravity\hopeful-faraday\SecureBrowser-v1.16.0-release.apk"
)

$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "=== Pushing main branch & Tag $TagName to GitHub ===" -ForegroundColor Cyan
git push origin main
git tag -f $TagName HEAD
git push origin $TagName --force

Write-Host "=== Retrieving GitHub Credentials ===" -ForegroundColor Cyan
$inputLines = @("protocol=https", "host=github.com", "")
$credOutput = $inputLines | git credential fill
$tokenLine = $credOutput | Where-Object { $_ -like "password=*" } | Select-Object -First 1

if (-not $tokenLine) {
    Write-Error "Could not retrieve GitHub token from git credential manager."
    exit 1
}

$token = $tokenLine.Substring(9).Trim()

$headers = @{
    "Authorization" = "token $token"
    "Accept"        = "application/vnd.github.v3+json"
    "User-Agent"    = "PowerShell-SecureBrowser"
}

$releaseBody = @"
## متصفح آمن — Secure Browser v1.16.0

### تصميم جديد واحترافي بالكامل للصفحة الرئيسية والخلفيات:
1. **6 خلفيات فنية احترافية للوضعين النهاري والليلي:**
   - خلفيات متدرجة بإضاءات محيطية هادئة (نسيم المحيط، الشفق القطبي، غروب دافئ، زمرد طبيعي، نيلي ملكي، وكلاسيكي هادئ) مع منتقي خلفيات بصري مباشر من الصفحة الرئيسية ومن الإعدادات.
2. **بطاقة ترحيب زجاجية ذكية ومربع بحث سريع:**
   - ترويسة بشارة الحماية النشطة، وبطاقة ترحيبية تتضمن مربع بحث وانتقال سريع + 3 اختصارات مباشرة للسجل والتنزيلات ومسح QR.
3. **عرض مطوّر لقنوات يوتيوب المسموحة والمواقع المحفوظة:**
   - بطاقات عريضة بعمودين لقنوات يوتيوب تعرض صورة القناة الدائرية الحقيقية واسم القناة والـ `@handle` مع شارة يوتيوب.
   - بطاقات زجاجية محسّنة للمواقع المحفوظة مع إطارات دائرية متباينة لأيقونات المواقع وتحديث تلقائي عند العودة من الإعدادات.
"@

Write-Host "=== Creating Release on GitHub ===" -ForegroundColor Cyan
$postData = @{
    tag_name         = $TagName
    target_commitish = "main"
    name             = "SecureBrowser $TagName"
    body             = $releaseBody
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json -Compress

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/yalaahamdy/SecureBrowser/releases" -Method Post -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($postData)) -ContentType "application/json; charset=utf-8"

Write-Host "Release created: $($release.html_url)" -ForegroundColor Green

Write-Host "=== Uploading APK Asset ===" -ForegroundColor Cyan
$uploadUrl = $release.upload_url -replace '\{\?name,label\}', "?name=SecureBrowser-$TagName-release.apk"

$uploadHeaders = @{
    "Authorization" = "token $token"
    "Content-Type"  = "application/vnd.android.package-archive"
    "User-Agent"    = "PowerShell-SecureBrowser"
}

$apkBytes = [System.IO.File]::ReadAllBytes($ApkPath)
$asset = Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $uploadHeaders -Body $apkBytes

Write-Host "APK uploaded successfully!" -ForegroundColor Green
Write-Host "Download URL: $($asset.browser_download_url)" -ForegroundColor Cyan
