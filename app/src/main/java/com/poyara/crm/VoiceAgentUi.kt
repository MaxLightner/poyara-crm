package com.poyara.crm

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One voice can contain several people, company relationships, roles, addresses
 * and meetings. User reviews the proposals before any database write.
 * Voice files remain on-device, including when analysis fails or network is off.
 */
@Composable
internal fun VoiceAgentPanel(
    db: CrmDb,
    audioPaths: List<String>,
    contacts: List<ContactEntry>,
    onImported: () -> Unit
) {
    val context = LocalContext.current
    val client = remember { VoiceAgentClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(client.isConfigured) }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<VoiceReview?>(null) }
    var importing by remember { mutableStateOf(false) }

    if (audioPaths.isEmpty()) {
        Text(
            "برای ثبت اطلاعات با صدا، ابتدا یک ویس ضبط کن.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    } else {
        Card {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row {
                    Text("دستیار صوتی پویارا", fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = true }) { Text("تنظیم اتصال") }
                }
                Text(
                    if (configured) "برای هر ویس، متن و اطلاعات استخراج‌شده را قبل از ثبت بررسی کن."
                    else "برای پردازش صوت، آدرس HTTPS و کلید سرویس خصوصی خودت را تنظیم کن.",
                    style = MaterialTheme.typography.bodySmall
                )
                audioPaths.distinct().filter { File(it).isFile }.forEachIndexed { index, path ->
                    val existing = remember(path, busyPath, review) { db.voiceReview(path) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "ویس " + (index + 1) +
                                when {
                                    existing?.applied == true -> " · ثبت‌شده"
                                    existing != null -> " · آماده بازبینی"
                                    else -> ""
                                },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            enabled = busyPath == null && !importing,
                            onClick = {
                                when {
                                    existing?.applied == true ->
                                        Toast.makeText(context, "این ویس قبلاً ثبت شده", Toast.LENGTH_SHORT).show()
                                    existing != null -> review = existing
                                    !client.isConfigured -> showSettings = true
                                    else -> {
                                        errorText = ""
                                        busyPath = path
                                        scope.launch {
                                            try {
                                                val jobId = UUID.randomUUID().toString()
                                                val result = withContext(Dispatchers.IO) {
                                                    client.analyze(File(path), jobId, contacts)
                                                }
                                                withContext(Dispatchers.IO) {
                                                    db.saveVoiceReview(jobId, path, result.toString())
                                                }
                                                review = db.voiceReview(path)
                                            } catch (exception: Exception) {
                                                errorText = exception.localizedMessage ?: "خطا در تحلیل صوت"
                                            } finally {
                                                busyPath = null
                                            }
                                        }
                                    }
                                }
                            }
                        ) {
                            if (busyPath == path) CircularProgressIndicator()
                            else Text(
                                when {
                                    existing?.applied == true -> "✓ انجام شد"
                                    existing != null -> "بازبینی"
                                    else -> "تحلیل صوت"
                                }
                            )
                        }
                    }
                }
                if (errorText.isNotBlank()) {
                    Text(errorText, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Text("فایل صوتی همچنان ذخیره است و می‌توانی دوباره تلاش کنی.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (showSettings) {
        var url by remember { mutableStateOf(client.endpoint) }
        var accessCode by remember { mutableStateOf("") }
        var settingsError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("اتصال به سرویس تحلیل صوت") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "کلید OpenAI را اینجا وارد نکن. فقط آدرس سرور پویارا و کلید اختصاصی پایلوت را وارد کن.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("آدرس HTTPS سرور") },
                        placeholder = { Text("https://voice.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = accessCode,
                        onValueChange = { accessCode = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("کلید دسترسی سرور (در صورت تغییر)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (settingsError.isNotBlank()) {
                        Text(settingsError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { client.saveSettings(url, accessCode) }
                        .onSuccess { configured = client.isConfigured; showSettings = false }
                        .onFailure { settingsError = it.message ?: "تنظیمات نامعتبر است" }
                }) { Text("ذخیره اتصال") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("انصراف") }
            }
        )
    }

    if (review != null) {
        val candidate = review!!
        val proposal = remember(candidate.resultJson) { JSONObject(candidate.resultJson) }
        val people = proposal.optJSONArray("contacts")
        val plans = proposal.optJSONArray("reminders")
        val questions = proposal.optJSONArray("questions")
        AlertDialog(
            onDismissRequest = { if (!importing) review = null },
            title = { Text("بازبینی اطلاعات ویس") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("متن صوت:", fontWeight = FontWeight.Bold)
                    Text(proposal.optString("transcript"),
                        style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text("اشخاص و مجموعه‌ها (" + (people?.length() ?: 0) + "):",
                        fontWeight = FontWeight.Bold)
                    for (i in 0 until (people?.length() ?: 0)) {
                        val item = people!!.optJSONObject(i) ?: continue
                        val pName = if (item.isNull("name")) "" else item.optString("name")
                        val company = if (item.isNull("company")) "" else item.optString("company")
                        val phone = if (item.isNull("phone")) "" else item.optString("phone")
                        val role = if (item.isNull("role")) "" else item.optString("role")
                        val address = if (item.isNull("address")) "" else item.optString("address")
                        Card {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(pName.ifBlank { "نام نامشخص" }, fontWeight = FontWeight.SemiBold)
                                listOf(company, role, phone, address)
                                    .filter { it.isNotBlank() }.forEach { Text(it) }
                            }
                        }
                    }
                    Text("قرارها (" + (plans?.length() ?: 0) + "):",
                        fontWeight = FontWeight.Bold)
                    for (i in 0 until (plans?.length() ?: 0)) {
                        val item = plans!!.optJSONObject(i) ?: continue
                        val title = item.optString("title")
                        val date = if (item.isNull("date_iso")) "نیازمند تاریخ دقیق"
                            else item.optString("date_iso")
                        val time = if (item.isNull("time_local")) "" else item.optString("time_local")
                        Text("• " + title + " · " + date + " " + time)
                    }
                    if ((questions?.length() ?: 0) > 0) {
                        HorizontalDivider()
                        Text("مواردی که نیاز به بررسی دارند:", fontWeight = FontWeight.Bold)
                        for (i in 0 until questions!!.length()) {
                            Text("• " + questions.optString(i))
                        }
                    }
                    Text(
                        "پس از تأیید، موارد قابل شناسایی ثبت می‌شوند. قرارهای با تاریخ مبهم و افراد تکراری نامشخص وارد نمی‌شوند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !importing && !candidate.applied,
                    onClick = {
                        importing = true
                        scope.launch {
                            try {
                                val report = withContext(Dispatchers.IO) {
                                    VoiceImportEngine.apply(context, db, candidate)
                                }
                                review = null
                                onImported()
                                Toast.makeText(
                                    context,
                                    "ثبت شد: " + report.contactsCreated + " نفر جدید، " +
                                        report.contactsUpdated + " ویرایش، " +
                                        report.remindersCreated + " یادآوری",
                                    Toast.LENGTH_LONG
                                ).show()
                                if (report.warnings.isNotEmpty()) {
                                    errorText = report.warnings.joinToString("\n")
                                }
                            } catch (error: Exception) {
                                errorText = error.message ?: "خطا در ذخیره نتیجه"
                            } finally {
                                importing = false
                            }
                        }
                    }
                ) { Text(if (importing) "در حال ثبت..." else "تأیید و ثبت اطلاعات") }
            },
            dismissButton = {
                TextButton(onClick = { review = null }, enabled = !importing) {
                    Text("فعلاً ثبت نکن")
                }
            }
        )
    }
}
