param(
    [string]$TagName = "v1.15.1",
    [string]$ApkPath = "C:\Users\Rocket\Documents\antigravity\hopeful-faraday\SecureBrowser-v1.15.1-release.apk"
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
## متصفح آمن — Secure Browser v1.15.1

### تحسينات تجربة الاستخدام والقوائم واليوتيوب:
1. **استبدال القوائم السفلية بالكامل بنظام قوائم عائمة وحوارات عصرية:**
   - قائمة المتصفح الرئيسية أصبحت **بطاقة منبثقة علوية** أسفل زر القائمة تحتوي على شريط اختصارات أيقونية سريعة + **شريط تكبير حي مدمج** (`-` / `+`) دون إغلاق القائمة + وصول سريع لقنوات يوتيوب المسموحة.
   - بطاقة معلومات الموقع تنسدل مباشرة أسفل أيقونة الدرع في الشريط العلوي مع زر إجراء سريع.
   - استبدال شاشات التبويبات والسجل السريع (مع إضافة بحث فوري) وقوائم إجراءات السجل والتنزيلات بنوافذ عائمة وحوارات وسطية عملية بدون قوائم سفلية مزعجة.
2. **معالجة ذكية لرسالة فتح تطبيق YouTube:**
   - إضافة زر مباشر **«فتح في المتصفح»** داخل الحوار لإتاحة القناة وفتح الرابط فورًا داخل المتصفح بعد موافقة الوالد.
   - استخراج رابط الويب المكافئ عند إلغاء الفتح في التطبيق الخارجي أو إظهار شاشة الحظر مع زر «السماح بهذه القناة» بدل التوقف الصامت.
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
