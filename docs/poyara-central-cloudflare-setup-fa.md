# پویارا — اتصال مرکزی به Cloudflare Workers AI

وضعیت این شاخه: Worker و روند CI آماده‌سازی شده‌اند؛ **بدون اتصال مالک Cloudflare، هنوز
روی حساب او استقرار واقعی نشده و اپلیکیشن اندروید هنوز از این Worker استفاده نمی‌کند.**
از دادن کلید محرمانه در چت یا ثبت کلید در فایل سورس خودداری کنید.

## اطلاعات مورد نیاز مالک حساب

۱. در [Cloudflare Dashboard](https://dash.cloudflare.com) وارد حسابی که با Google ساختید شوید.
از صفحه Account Home یا بخش Workers & Pages، **Account ID** را کپی کنید.
این شناسه محرمانه نیست و برای مسیر استقرار لازم است.

۲. از [GitHub → Secrets and variables → Actions](https://github.com/MaxLightner/poyara-crm/settings/secrets/actions)
برای مخزن پویارا، دو **Repository Secret** بسازید:

- `CLOUDFLARE_ACCOUNT_ID`: همان Account ID.
- `CLOUDFLARE_API_TOKEN`: توکن اختصاصی از Cloudflare Dashboard → Account API tokens → Create Token
  با مجوز محدود «Edit Cloudflare Workers» فقط برای همین حساب.

۳. برای جلوگیری از استقرار ناخواسته، در صفحه Repository Variables یک متغیر
`POYARA_CLOUD_BACKEND_ENABLED=true` بگذارید. این کار را فقط بعد از آماده‌سازی
اسرار استقرار انجام دهید.

۴. پس از نخستین استقرار Worker، یک secret مستقل به نام
`POYARA_APP_ACCESS_KEY` با مقدار تصادفی طولانی (حداقل ۳۲ کاراکتر)
برای خود Worker تعریف می‌شود. این کد **توکن مدیریتی Cloudflare نیست**؛
کاربردش فقط محدودکردن استفاده از Agent است. نباید به شکل باز در فایل
GitHub یا APK منتشر شود؛ شیوه امن جفت‌سازی دستگاه‌های همکار جداگانه تکمیل می‌شود.

۵. سپس `https://<worker-name>.<subdomain>.workers.dev/health` را
بررسی کنید؛ نام و زیردامنه واقعی از خروجی استقرار مشخص می‌شوند.
نباید آدرس Worker را حدس زد.

## مدل و اجرا

- Worker دارای binding بومی `AI` است. مدل متنی
  `@cf/zai-org/glm-4.7-flash` مستقیماً روی Cloudflare اجرا می‌شود.
- توکن Cloudflare فقط در GitHub Secrets برای انتشار Worker باقی می‌ماند؛
  نه در گوشی کاربر و نه در پاسخ Worker.
- نقطه ورود مرکزی `POST /v1/text/analyze` پیشنهادهای JSON برای CRM برمی‌گرداند؛
  خود Worker تغییر پایگاه داده را اجرا نمی‌کند.
- موتور Kotlin در گوشی وظیفه اعتبارسنجی دستور، انتخاب مخاطب و ثبت یادآوری را دارد.
- تماس و کار با مخاطبین بدون اینترنت همچنان محلی‌اند.
- در صورت نیاز برای فایل صوتی، رونویسی با Whisper باید جداگانه توسعه و آزمون شود.

## وضعیت راه‌اندازی و مراقبت

- ثبت‌نام با Google در Cloudflare به‌معنی ایجاد API Token یا فعال‌شدن خودکار Worker نیست.
- Cloudflare محدودیت روزانه مصرف، محدودیت منطقه و سیاست‌های سوءاستفاده دارد.
- محافظت از endpoint ضروری است، حتی اگر اطلاعات مشتری محرمانه نباشد؛
  درخواست عمومی می‌تواند سهمیه رایگان Agent را تمام کند.
- قبل از توزیع APK به همکاران، اتصال Android به Worker واقعی، احراز هویت
  دستگاه‌ها، نرخ‌محدودسازی، روزهای قطع اینترنت و تجربه ویس فارسی باید آزمایش شوند.

مراجع رسمی:
https://developers.cloudflare.com/workers/ci-cd/external-cicd/github-actions/
https://developers.cloudflare.com/workers-ai/models/glm-4.7-flash/
