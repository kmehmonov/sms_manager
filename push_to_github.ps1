# ============================================================
# GitHub'ga yuklash skripti (PowerShell)
# Ishlatish: PowerShell'da o'ng tugma → "Run with PowerShell"
# ============================================================

$RepoUrl = "https://github.com/kmehmonov/sms_manager.git"
$ProjectDir = $PSScriptRoot  # Skript joylashgan papka

Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "   SMS Manager — GitHub'ga yuklash skripti" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# Papkaga o'tish
Set-Location $ProjectDir
Write-Host "📁 Papka: $ProjectDir" -ForegroundColor Yellow

# Git o'rnatilganligini tekshirish
try {
    $gitVersion = git --version 2>&1
    Write-Host "✓ Git topildi: $gitVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Git o'rnatilmagan!" -ForegroundColor Red
    Write-Host "   https://git-scm.com/download/win saytidan yuklab o'rnating" -ForegroundColor Yellow
    Read-Host "Chiqish uchun Enter bosing"
    exit 1
}

# Git repo mavjudligini tekshirish
if (Test-Path ".git") {
    Write-Host "✓ Git repo allaqachon mavjud" -ForegroundColor Green
} else {
    Write-Host "🔧 Git repo yangi yaratilmoqda..." -ForegroundColor Yellow
    git init
    Write-Host "✓ Git repo yaratildi" -ForegroundColor Green
}

# Remote URL ni tekshirish / qo'shish
$remotes = git remote -v 2>&1
if ($remotes -match "origin") {
    Write-Host "✓ Remote 'origin' allaqachon mavjud" -ForegroundColor Green
    # URL ni yangilaymiz
    git remote set-url origin $RepoUrl
    Write-Host "✓ Remote URL yangilandi: $RepoUrl" -ForegroundColor Green
} else {
    git remote add origin $RepoUrl
    Write-Host "✓ Remote qo'shildi: $RepoUrl" -ForegroundColor Green
}

# .gitignore'da gradle-wrapper.jar ni saqlash uchun qoida
Write-Host ""
Write-Host "🔧 gradle-wrapper.jar sozlamasi..." -ForegroundColor Yellow

# gradle-wrapper.jar ni .gitignore'dan chiqarish
$gitignoreContent = Get-Content ".gitignore" -Raw -ErrorAction SilentlyContinue
if ($gitignoreContent -notmatch "!gradle/wrapper/gradle-wrapper.jar") {
    Add-Content ".gitignore" "`n# Gradle wrapper jar'ni git'ga qo'shish (kerak!)`n!gradle/wrapper/gradle-wrapper.jar"
    Write-Host "✓ .gitignore yangilandi" -ForegroundColor Green
}

# Barcha fayllarni staging'ga qo'shamiz
Write-Host ""
Write-Host "📦 Fayllar qo'shilmoqda..." -ForegroundColor Yellow
git add .
git status --short

# Commit yaratamiz
Write-Host ""
Write-Host "💾 Commit yaratilmoqda..." -ForegroundColor Yellow
$commitMessage = "feat: SMS Manager Android ilovasi — CSV orqali avtomatik SMS yuborish"
git commit -m $commitMessage 2>&1 | ForEach-Object {
    if ($_ -match "nothing to commit") {
        Write-Host "ℹ️  O'zgarishlar yo'q — yangi commit kerak emas" -ForegroundColor Cyan
    } else {
        Write-Host $_ -ForegroundColor White
    }
}

# GitHub'ga yuklash
Write-Host ""
Write-Host "🚀 GitHub'ga yuklanmoqda: $RepoUrl" -ForegroundColor Yellow
Write-Host "   (GitHub username va parol/token so'ralishi mumkin)" -ForegroundColor Gray
Write-Host ""

# Main branch'ga yuborish
git push -u origin main 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "✅ MUVAFFAQIYATLI YUKLANDI!" -ForegroundColor Green
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor Green
    Write-Host ""
    Write-Host "🔗 Repo manzili:" -ForegroundColor Cyan
    Write-Host "   https://github.com/kmehmonov/sms_manager" -ForegroundColor White
    Write-Host ""
    Write-Host "🤖 GitHub Actions APK yaratmoqda:" -ForegroundColor Cyan
    Write-Host "   https://github.com/kmehmonov/sms_manager/actions" -ForegroundColor White
    Write-Host ""
    Write-Host "⏳ 5-10 daqiqadan so'ng Actions bo'limidan APK yuklab oling" -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "❌ Yuklashda xato!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Mumkin bo'lgan sabablar:" -ForegroundColor Yellow
    Write-Host "  1. GitHub'da repo yaratilmagan" -ForegroundColor White
    Write-Host "     → https://github.com/new ga o'ting, 'sms_manager' nomli repo yarating" -ForegroundColor Gray
    Write-Host "  2. Internet aloqasi yo'q" -ForegroundColor White
    Write-Host "  3. GitHub parol/token noto'g'ri" -ForegroundColor White
    Write-Host "     → https://github.com/settings/tokens dan Personal Access Token oling" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Token bilan yuborish:" -ForegroundColor Yellow
    Write-Host "  git push https://TOKEN@github.com/kmehmonov/sms_manager.git main" -ForegroundColor Gray
}

Write-Host ""
Read-Host "Chiqish uchun Enter bosing"
