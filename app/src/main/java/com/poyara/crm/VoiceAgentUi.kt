package com.poyara.crm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Display queued/processed audio and collect approval before importing. */
@Composable
internal fun VoiceAgentPanel(
    db: CrmDb,
    audioPaths: List<String>,
    contacts: List<ContactEntry>,
    onImported: () -> Unit,
    onTranscribed: (String) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { VoiceServerSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(settings.configured) }
    var showSettings by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<VoiceReview?>(null) }
    var importing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var transcribingPath by remember { mutableStateOf<String?>(null) }
    var transcriptionStage by remember { mutableStateOf("") }
    var transcriptionError by remember { mutableStateOf("") }
    var transcriptionResult by remember { mutableStateOf<String?>(null) }
    var editableTranscript by remember { mutableStateOf("") }
    var recognizer by remember { mutableStateOf<VoiceFileTranscriber?>(null) }
    DisposableEffect(Unit) {
        onDispose { recognizer?.cancel() }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(
            context, "برای اعلان یادآوری، اجازه اعلان‌ها را از تنظیمات گوشی فعال کن",
            Toast.LENGTH_LONG
        ).show()
    }

    LaunchedEffect(audioPaths) {
        audioPaths.distinct().forEach { path ->
            if (File(path).isFile) {
                VoiceQueue.record(context, File(path), contacts.firstOrNull()?.id ?: 0L)
            }
        }
    }
    LaunchedEffect(Unit) {
        VoiceQueue.schedulePending(context)
        while (true) {
            delay(2700L)
            refreshTick++
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text("دستیار صوتی پویارا", fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = true }) {
                    Text(if (configured) "تنظیم سرور" else "اتصال به سرور")
                }
            }
            Text(if (configured)
                    "ویس‌ها پس از وصل اینترنت ارسال و روی سرور تحلیل می‌شوند."
                else "ویس‌ها روی گوشی می‌مانند. برای تحلیل، آدرس سرور را یک‌بار تنظیم کن.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            if (audioPaths.isEmpty()) {
                Text("هنوز ویسی ضبط نکرده‌ای.",
                    style = MaterialTheme.typography.bodySmall)
            }
            audioPaths.distinct().filter { File(it).isFile }.forEachIndexed { index, path ->
                val existing = remember(path, refreshTick) { db.voiceReview(path) }
                val upload = remember(path, refreshTick) { db.voiceUpload(path) }
                val status = when {
                    existing?.applied == true -> "✓ ثبت شده"
                    existing != null -> "آماده تأیید"
                    upload?.status == "uploading" -> upload.lastError.ifBlank { "در حال ارسال..." }
                    upload?.status == "retry" -> "ارسال دوباره پس از اتصال / رفع خطای سرور"
                    upload?.status == "failed" -> "ارسال ناموفق"
                    !configured -> "روی گوشی ذخیره شده؛ سرور هنوز تنظیم نشده"
                    else -> "در صف ارسال خودکار"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("ویس " + (index + 1), fontWeight = FontWeight.SemiBold)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        val percent = if (upload?.status == "uploading")
                            Regex("ارسال فایل: (\\d+)٪").find(upload.lastError)?.groupValues?.get(1)?.toIntOrNull()
                            else null
                        if (percent != null) {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (upload?.status == "uploading") {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if ((upload?.status == "failed" || upload?.status == "retry") &&
                            upload.lastError.isNotBlank()) {
                            Text(upload.lastError, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (transcribingPath == path) {
                        OutlinedButton(onClick = {
                            recognizer?.cancel()
                            transcribingPath = null
                            transcriptionStage = ""
                        }) { Text("توقف") }
                    } else {
                        OutlinedButton(
                            enabled = transcribingPath == null,
                            onClick = {
                                transcriptionError = ""
                                transcriptionStage = ""
                                transcribingPath = path
                                val engine = VoiceFileTranscriber(context.applicationContext)
                                recognizer = engine
                                engine.transcribe(File(path),
                                    onStage = { transcriptionStage = it },
                                    onFinish = { text, error ->
                                        transcribingPath = null
                                        recognizer = null
                                        if (error != null) transcriptionError = error
                                        else if (text != null) {
                                            editableTranscript = text
                                            transcriptionResult = text
                                        }
                                    }
                                )
                            }
                        ) { Text("تبدیل به متن") }
                    }
                    if (existing != null && !existing.applied) {
                        Button(onClick = { review = existing }) { Text("بازبینی") }
                    } else if (upload?.status == "failed") {
                        OutlinedButton(onClick = {
                            if (configured) VoiceQueue.retry(context, path)
                            else showSettings = true
                            refreshTick++
                        }) { Text("تلاش مجدد") }
                    } else if (!configured) {
                        OutlinedButton(onClick = { showSettings = true }) { Text("تنظیم") }
                    }
                }
                if (transcribingPath == path) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(transcriptionStage.ifBlank { "در حال آماده‌سازی فایل…" },
                        style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
            if (transcriptionError.isNotBlank()) {
                Text(transcriptionError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (errorText.isNotBlank()) {
                Text(errorText, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (transcriptionResult != null) {
        AlertDialog(
            onDismissRequest = { transcriptionResult = null },
            title = { Text("متن تشخیص‌داده‌شده از فایل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("نتیجه آزمایشی است. نام‌ها و شماره‌ها را بررسی کن. بعضی سرویس‌های گوشی به‌جای فایل، میکروفون را فعال می‌کنند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(
                        value = editableTranscript,
                        onValueChange = { editableTranscript = it },
                        label = { Text("متن قابل ویرایش") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 350.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (editableTranscript.isNotBlank()) onTranscribed(editableTranscript)
                    transcriptionResult = null
                }) { Text("افزودن به یادداشت مخاطب") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("متن ویس پویارا", editableTranscript))
                    transcriptionResult = null
                    Toast.makeText(context, "متن کپی شد", Toast.LENGTH_SHORT).show()
                }) { Text("کپی متن") }
            }
        )
    }

    if (showSettings) {
        var url by remember { mutableStateOf(settings.endpoint) }
        var pairingCode by remember { mutableStateOf("") }
        var configError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("اتصال دستیار صوتی به سرور") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("بدون ثبت‌نام یا ایمیل؛ آدرس HTTPS و کد اتصال یک‌بار ثبت می‌شود.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("آدرس سرور HTTPS") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pairingCode, onValueChange = { pairingCode = it },
                        label = { Text("کد اتصال دستگاه") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    if (configError.isNotBlank())
                        Text(configError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        settings.save(url,pairingCode)
                        configured = true
                        showSettings = false
                        VoiceQueue.schedulePending(context)
                    } catch (e: Exception) { configError = e.localizedMessage ?: "تنظیم نامعتبر" }
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
        val observations = proposal.optJSONArray("observations")
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
                    if ((observations?.length() ?: 0) > 0) {
                        HorizontalDivider()
                        Text("نکات تکمیلی گفته‌شده:", fontWeight = FontWeight.Bold)
                        for (i in 0 until observations!!.length()) {
                            Text("• " + observations.optString(i))
                        }
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
                                if (report.remindersCreated > 0 && Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
