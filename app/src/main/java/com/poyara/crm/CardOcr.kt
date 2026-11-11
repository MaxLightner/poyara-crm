package com.poyara.crm

import android.content.Context
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

data class ScannedBusinessCard(
    val personName: String,
    val company: String,
    val phone: String,
    val address: String,
    val email: String,
    val website: String,
    val rawText: String
)

object CardOcr {
    fun recognize(context: Context, image: File): ScannedBusinessCard {
        val root = File(context.filesDir, "tesseract")
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        val model = File(tessdata, "fas.traineddata")
        if (!model.isFile || model.length() < 100_000L) {
            context.assets.open("tessdata/fas.traineddata").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val bitmap = BitmapFactory.decodeFile(image.absolutePath)
            ?: error("تصویر کارت قابل خواندن نیست")
        val persianText = runCatching {
            val api = TessBaseAPI()
            try {
                check(api.init(root.absolutePath, "fas")) {
                    "موتور تشخیص متن فارسی آماده نشد"
                }
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                api.setImage(bitmap)
                api.getUTF8Text().orEmpty()
            } finally {
                api.recycle()
            }
        }.getOrDefault("")

        val latinText = runCatching {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
            } finally {
                recognizer.close()
            }
        }.getOrDefault("")

        bitmap.recycle()
        val combined = listOf(persianText, latinText)
            .filter { it.isNotBlank() }.distinct().joinToString("\\n")
        check(combined.isNotBlank()) { "متنی از کارت تشخیص داده نشد" }
        return parse(combined)
    }

    private fun parse(rawText: String): ScannedBusinessCard {
        val text = normalizeDigits(rawText)
        val lines = text.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() }.toList()
        val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .find(text)?.value.orEmpty()
        val website = Regex("(?:https?://)?(?:www\\.)?[A-Z0-9-]+\\.(?:ir|com|net|org|co|io)(?:/\\S*)?",
            RegexOption.IGNORE_CASE).find(text)?.value.orEmpty()
        val phone = lines.firstNotNullOfOrNull { line ->
            val candidate = line.replace(Regex("[^0-9+]+"), "")
            val digits = candidate.filter(Char::isDigit)
            candidate.takeIf { digits.length in 8..14 }
        }.orEmpty()
        val addressLine = lines.firstOrNull {
            listOf("آدرس", "نشانی", "خیابان", "بلوار", "کوچه", "میدان", "پلاک", "address")
                .any { key -> it.contains(key, ignoreCase = true) }
        }.orEmpty()
        val companyLine = lines.firstOrNull {
            it != phone && listOf(
                "مبلمان", "کابینت", "شرکت", "مجموعه", "فروشگاه", "گروه",
                "استودیو", "معماری", "طراحی", "صنایع", "تولیدی", "کارخانه"
            ).any { key -> it.contains(key, ignoreCase = true) }
        }.orEmpty()
        val personLine = lines.firstOrNull {
            it != phone && it != companyLine &&
                listOf("مدیرعامل", "مدیر", "مسئول", "کارشناس", "آقای", "خانم", "جناب", "سرکار", "name:", "manager:", "mr.", "ms.")
                    .any { key -> it.contains(key, ignoreCase = true) }
        }.orEmpty()
        val person = personLine.replace(
            Regex("^(مدیرعامل|مدیر|مسئول|کارشناس|جناب آقای|آقای|سرکار خانم|خانم|name|manager|mr\\.|ms\\.)\\s*[:：-]?\\s*",
                RegexOption.IGNORE_CASE),
            ""
        ).trim()
        val cleanCompany = companyLine.replace(
            Regex("^(company|brand|مجموعه|شرکت)\\s*[:：-]?\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        val fallbackPerson = if (person.isBlank()) {
            lines.firstOrNull { it != phone && it != companyLine && it != addressLine &&
                !it.contains("@") && !it.contains("www.", true) }.orEmpty()
        } else person
        return ScannedBusinessCard(
            personName = fallbackPerson.take(70),
            company = cleanCompany.take(90),
            phone = phone,
            address = addressLine.removePrefix("آدرس").removePrefix("نشانی").trim(' ', ':', '：', '-').take(180),
            email = email,
            website = website,
            rawText = rawText.trim().take(1800)
        )
    }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(when (ch) {
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                else -> ch
            })
        }
    }
}
