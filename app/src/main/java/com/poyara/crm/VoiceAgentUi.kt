package com.poyara.crm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * One job: convert a completed saved M4A with the Android/Google speech service.
 * No account, email, HTTP server, private key, paid API or live text on screen.
 * Original recordings and older server job rows remain untouched.
 */
@Composable
internal fun VoiceAgentPanel(
    audioPaths: List<String>,
    onTranscribed: (String) -> Unit
) {
    val context = LocalContext.current
    val transcriptPrefs = remember {
        context.getSharedPreferences("voice_local_transcripts", Context.MODE_PRIVATE)
    }
    val transcriberState = remember { mutableStateOf<VoiceFileTranscriber?>(null) }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var openPath by remember { mutableStateOf<String?>(null) }
    var editableText by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { transcriberState.value?.cancel() }
    }

    fun openTranscript(path: String) {
        openPath = path
        editableText = transcriptPrefs.getString(path, "").orEmpty()
    }
    fun startTranscription(path: String) {
        if (busyPath != null) return
        failure = ""
        stage = "در حال آماده‌سازی فایل ضبط‌شده..."
        busyPath = path
        val reader = VoiceFileTranscriber(context.applicationContext)
        transcriberState.value = reader
        reader.transcribe(File(path),
            onStage = { value -> stage = value },
            onFinish = { text, error ->
                transcriberState.value = null
                busyPath = null
                stage = ""
                if (text.isNullOrBlank()) {
                    failure = error ?: "سرویس گوگل فایل را تبدیل نکرد. فایل صوتی محفوظ است."
                } else {
                    transcriptPrefs.edit().putString(path, text).apply()
                    reload++
                    openTranscript(path)
                }
            }
        )
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("تبدیل صوت به متن با سرویس گفتار گوشی", fontWeight = FontWeight.Bold)
            Text(
                "فقط بعد از پایان ضبط کار می‌کند. متن حین صحبت نشان داده نمی‌شود و هیچ سروری لازم نیست. " +
                    "تشخیص فایل و فارسی به سرویس گوگل نصب‌شده روی گوشی بستگی دارد.",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall
            )
            if (audioPaths.isEmpty()) {
                Text("برای آزمایش، بالاتر «ضبط صوت» را بزن؛ پس از پایان ضبط، تبدیل را انتخاب کن.")
            }
            audioPaths.distinct().filter { File(it).isFile }.forEachIndexed { index, path ->
                val savedText = remember(path, reload) {
                    transcriptPrefs.getString(path, "").orEmpty()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ویس " + (index + 1), fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                busyPath == path -> stage.ifBlank { "در حال تبدیل..." }
                                savedText.isNotBlank() -> "متن تشخیص‌داده‌شده ذخیره شده"
                                else -> "فایل صوتی محفوظ است؛ منتظر تبدیل"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (busyPath == path) {
                        TextButton(onClick = {
                            transcriberState.value?.cancel()
                            transcriberState.value = null
                            busyPath = null
                            stage = ""
                        }) { Text("لغو") }
                    } else if (savedText.isNotBlank()) {
                        TextButton(onClick = { openTranscript(path) }) { Text("متن") }
                        OutlinedButton(
                            enabled = busyPath == null,
                            onClick = { startTranscription(path) }
                        ) { Text("دوباره") }
                    } else {
                        Button(
                            enabled = busyPath == null,
                            onClick = { startTranscription(path) }
                        ) { Text("تبدیل به متن") }
                    }
                }
                if (busyPath == path) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()
            }
            if (failure.isNotBlank()) {
                Text(failure, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (openPath != null) {
        val recognized = VoiceLocalFacts.extract(editableText)
        AlertDialog(
            onDismissRequest = { openPath = null },
            title = { Text("بازبینی متن ویس") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "متن را بررسی و اصلاح کن. پویارا فقط اطلاعات واضح را به فرم پیشنهاد می‌دهد؛ " +
                            "برای چند مخاطب یا قرار پیچیده فعلاً متن کامل در یادداشت نگه داشته می‌شود.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = editableText,
                        onValueChange = { editableText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        label = { Text("متن قابل ویرایش") }
                    )
                    HorizontalDivider()
                    Text("اطلاعات قابل تشخیص:", fontWeight = FontWeight.SemiBold)
                    Text("نام: " + recognized.personName.ifBlank { "نامشخص" })
                    Text("شرکت: " + recognized.company.ifBlank { "نامشخص" })
                    Text("شماره: " + recognized.phone.ifBlank { "نامشخص یا چند شماره" })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val path = openPath
                    if (path != null) {
                        transcriptPrefs.edit().putString(path, editableText).apply()
                        reload++
                    }
                    if (editableText.isNotBlank()) onTranscribed(editableText)
                    openPath = null
                    Toast.makeText(context, "متن به فرم منتقل شد؛ موارد را بازبینی کن", Toast.LENGTH_LONG).show()
                }) { Text("انتقال به فرم") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("متن ویس پویارا", editableText))
                    Toast.makeText(context, "متن کپی شد", Toast.LENGTH_SHORT).show()
                }) { Text("کپی متن") }
            }
        )
    }
}
