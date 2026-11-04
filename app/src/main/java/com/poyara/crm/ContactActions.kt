package com.poyara.crm

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Launches system apps; does not require CALL_PHONE or SEND_SMS permission. */
fun openDialer(context: Context, phone: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.trim(), null))
    context.startActivity(intent)
}.isSuccess

/** Returns true only when the SMS composer opens, never claims the SMS was sent. */
fun openSmsComposer(context: Context, phone: String, body: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone.trim(), null)).apply {
        putExtra("sms_body", body)
    }
    context.startActivity(intent)
}.isSuccess

fun smsTemplate(kind: String, company: String): String {
    val salutation = if (company.isBlank()) "سلام، وقت بخیر." else "سلام، وقت بخیر. از طرف پویارا خدمت مجموعه $company پیام می‌دهم."
    return when (kind) {
        "پیگیری" -> "$salutation پیرو صحبت قبلی، خواستم موضوع مورد بحث را پیگیری کنم. ممنون می‌شوم زمان مناسب را اعلام کنید."
        "هماهنگی جلسه" -> "$salutation برای هماهنگی جلسه و ارائه دمو، چه روز و ساعتی برایتان مناسب است؟"
        "ارسال پیشنهاد" -> "$salutation پیشنهاد همکاری آماده شده است. لطفاً بفرمایید از چه طریقی ارسال کنم."
        "تشکر" -> "$salutation بابت وقتی که برای جلسه اختصاص دادید ممنونم. خوشحال می‌شوم همکاری را ادامه دهیم."
        else -> ""
    }
}
