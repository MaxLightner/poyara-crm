# راه‌اندازی یک‌باره Agent مرکزی پویارا 0.21

این کد در GitHub آماده است؛ **تا زمانی که Secrets در GitHub ثبت و Workflow اجرا نشود،
سرویس روی Cloudflare فعال و APK متصل منتشر نشده است.** ساخت بدون Secret به‌صورت
پیش‌نمایش آفلاین انجام می‌شود و در انتشار اصلی عرضه نمی‌شود.

## کارهایی که فقط مالک حساب باید انجام دهد

**Account ID قبلاً در `cloudflare-worker/wrangler.jsonc` ثبت شده است و نباید دوباره در گوشی وارد شود.**

### ۱. کلیدهای قبلی را باطل کنید

توکن Cloudflare و R2 S3 Access Key که در مکالمه به‌صورت متن عادی ارسال شده‌اند
دیگر محرمانه محسوب نمی‌شوند. از Cloudflare Dashboard آنها را revoke کنید.
کلید S3 به این پروژه و Workers AI احتیاجی ندارد و **نباید به GitHub یا گوشی داده شود**.

### ۲. فقط یک توکن جدید مخصوص انتشار Worker بسازید

از Cloudflare Dashboard → Account API Tokens → Create Token، توکن محدود
به همین Account با مجوز **Edit Cloudflare Workers** ایجاد کنید. این توکن
برای Wrangler و GitHub Actions است، نه برای اجرا در APK.

در GitHub مخزن پویارا، Settings → Secrets and variables → Actions،
در قسمت **Repository Secrets** دو مورد زیر را بسازید:

| Name | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | توکن *جدید* با مجوز Edit Cloudflare Workers |
| `POYARA_APP_ACCESS_KEY` | یک رمز تصادفی مستقل ۶۴ کاراکتری، مثلاً خروجی `openssl rand -hex 32` |

**مقدارها را در چت یا فایل عمومی GitHub قرار ندهید.** رمز دوم
فقط برای محدودکردن دسترسی اپ به Agent است و از توکن اصلی Cloudflare
جداست. برای استفاده آزمایشی داخل APK قرار می‌گیرد و قابل استخراج است؛
به همین دلیل Worker محدودیت درخواست دارد. برای انتشار عمومی باید
احراز هویت دستگاهی/سازمانی اضافه شود.

### ۳. مجوز انتشار را روشن کنید

در همان صفحه GitHub، از قسمت Variables یک Repository Variable بسازید:

`POYARA_CLOUD_BACKEND_ENABLED=true`

سپس GitHub → Actions → **Deploy central POYARA Workers AI backend**
→ Run workflow → branch main.

این Workflow به ترتیب:

1. تست‌های Worker را اجرا می‌کند؛
2. با توکن Workers، Worker را روی حساب شما منتشر می‌کند؛
3. رمز مستقل اپ را به صورت Secret روی Worker ذخیره می‌کند؛
4. از خروجی واقعی Wrangler، آدرس HTTPS سرویس را می‌گیرد؛
5. APK اندروید پویارا را **بدون هیچ کلید Cloudflare** و با URL واقعی Worker
   و رمز محدود اپ می‌سازد؛
6. نسخه قابل نصب را در GitHub Releases منتشر می‌کند.

اگر Workflow در مرحله Deploy خطای 403 داد، احتمالاً توکن ایجادشده
برای R2 بوده یا مجوز Workers Edit ندارد. اگر URL تولید نشد،
Workers.dev را برای همان حساب فعال کنید و Workflow را دوباره اجرا کنید.

### ۴. آزمون گوشی

در APK منتشرشده، هیچ گزینه‌ای برای وارد کردن Account ID یا API Token
وجود ندارد. در تنظیمات فقط اجازه یک‌باره ارسال متن/ویس و بررسی اتصال
Agent دیده می‌شود. تمام افراد تیم از مدل یکسان در حساب Cloudflare استفاده می‌کنند؛
اما **پایگاه داده مخاطبین همچنان محلی/Google Contacts است و صرفاً با
اشتراک Agent، همه فایل‌های CRM همگام‌سازی نمی‌شوند.**

## معماری و حدود

- مدل تحلیل متن: `@cf/zai-org/glm-4.7-flash` با Workers AI binding
- رونویسی اختیاری M4A: `@cf/openai/whisper`، محدود به ۴ مگابایت در هر ویس
- URL: `/v1/text/analyze` و `/v1/audio/transcribe`
- دسترسی: رمز مجزای اپ، HTTPS و محدودسازی ۳۰ درخواست در هر دقیقه
  برای هر IP (محدودسازی تقریبی Cloudflare)
- اپ فقط دستورهای مجاز CRM را اجرا می‌کند؛ مدل اجازه SQL مستقیم یا
  حذف نامحدود ندارد. داده‌های مبهم نیاز به تأیید دارند.
- طرح Workers AI رایگان روزانه ۱۰٬۰۰۰ Neurons سقف دارد. شبکه ایران
  و کیفیت Whisper فارسی روی گوشی حقیقی هنوز آزمایش نشده است.
- امضای APK این نسخه همچنان آزمایشی است؛ پیش از هر نصب ناسازگار
  نسخه قبلی از CRM پشتیبان قابل بازیابی بگیرید.

## لینک‌های تنظیمات

Cloudflare Dashboard: https://dash.cloudflare.com/
GitHub Secrets: https://github.com/MaxLightner/poyara-crm/settings/secrets/actions
GitHub Actions: https://github.com/MaxLightner/poyara-crm/actions
GitHub Releases: https://github.com/MaxLightner/poyara-crm/releases
Cloudflare deploy guide: https://developers.cloudflare.com/workers/ci-cd/external-cicd/github-actions/
