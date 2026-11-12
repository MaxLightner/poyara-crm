package com.poyara.crm

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

/** Experimental recognition without server, email, or paid API. */
@Composable
internal fun VoiceGoogleTrialPanel(
    audioPaths: List<String>,
    onTranscribed: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voice_google_trial_texts", android.content.Context.MODE_PRIVATE) }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf<VoiceFileTranscriber?>(null) }
    var dialogPath by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var newDictationPath by remember { mutableStateOf<String?>(null) }

    val speechActivity = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = newDictationPath
        newDictationPath = null
        if (result.resultCode == Activity.RESULT_OK && path != null) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) {
                prefs.edit().putString(path, text).apply()
                dialogPath = path
                editor = text
                revision++
            } else error = "متنی از سرویس تشخیص گفتار دریافت نشد."
        }
    }

    DisposableEffect(Unit) { onDispose { engine?.cancel() } }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("آزمایش تایپ صوتی گوگل", fontWeight = FontWeight.Bold)
            Text("بدون ایمیل یا تنظیمات سرور. ویس روی گوشی حفظ می‌شود و هنگام ضبط متنی نمایش داده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall)
            Text(
                if (Build.VERSION.SDK_INT < 33)
                    "اندروید این گوشی زیر ۱۳ است. سرویس گفتار استاندارد این نسخه فایل ضبط‌شده را مستقیم نمی‌خواند؛ دیکته جدید یعنی لازم است دوباره صحبت کنی."
                else
                    "تبدیل مستقیم فایل آزمایشی است؛ ممکن است سرویس نصب‌شده دریافت فایل یا زبان فارسی را پشتیبانی نکند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            if (audioPaths.isEmpty()) Text("ابتدا با دکمه ضبط صوت، ویس را ضبط کن.")
            audioPaths.distinct().filter { File(it).isFile }.forEachIndexed { index, path ->
                val saved = remember(path, revision) { prefs.getString(path, "").orEmpty() }
                Text("ویس " + (index + 1), fontWeight = FontWeight.SemiBold)
                if (busyPath == path) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stage, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        engine?.cancel()
                        engine = null
                        busyPath = null
                    }) { Text("لغو") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            OutlinedButton(enabled = busyPath == null, onClick = {
                                error = ""
                                busyPath = path
                                stage = "در حال آماده‌سازی..."
                                val transcriber = VoiceFileTranscriber(context.applicationContext)
                                engine = transcriber
                                transcriber.transcribe(File(path),
                                    onStage = { stage = it },
                                    onFinish = { transcript, message ->
                                        busyPath = null
                                        engine = null
                                        if (message != null) error = message
                                        else if (transcript != null && transcript.isNotBlank()) {
                                            prefs.edit().putString(path, transcript).apply()
                                            dialogPath = path
                                            editor = transcript
                                            revision++
                                        }
                                    })
                            }) { Text("تبدیل فایل به متن") }
                        } else {
                            OutlinedButton(enabled = busyPath == null, onClick = {
                                error = ""
                                newDictationPath = path
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "صحبتت را دوباره بگو")
                                }
                                runCatching { speechActivity.launch(intent) }
                                    .onFailure { error = "برنامه تایپ صوتی گوگل روی گوشی پیدا نشد." }
                            }) { Text("دیکته جدید گوگل") }
                        }
                        if (saved.isNotBlank()) {
                            TextButton(onClick = { dialogPath = path; editor = saved }) {
                                Text("نمایش متن")
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (dialogPath != null) {
        AlertDialog(
            onDismissRequest = { dialogPath = null },
            title = { Text("متن تشخیص‌داده‌شده") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("متن را بررسی کن. تایپ صوتی گوگل فقط متن می‌دهد؛ روابط مشتریان و جلسات را به‌تنهایی تحلیل نمی‌کند.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = editor,
                        onValueChange = { editor = it },
                        label = { Text("متن قابل ویرایش") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val path = dialogPath
                    if (path != null && editor.isNotBlank()) {
                        prefs.edit().putString(path, editor).apply()
                        onTranscribed(editor)
                        revision++
                        Toast.makeText(context, "متن به یادداشت افزوده شد", Toast.LENGTH_SHORT).show()
                    }
                    dialogPath = null
                }) { Text("افزودن به یادداشت") }
            },
            dismissButton = {
                TextButton(onClick = { dialogPath = null }) { Text("بستن") }
            }
        )
    }
}
