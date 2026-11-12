package com.poyara.crm

import android.Manifest
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onImported: () -> Unit
) {
    val context = LocalContext.current
    val auth = remember { PoyaraAuth(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(auth.hasSession) }
    var showLogin by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<VoiceReview?>(null) }
    var importing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
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
                TextButton(onClick = {
                    if (loggedIn) {
                        auth.logout()
                        loggedIn = false
                    } else showLogin = true
                }) { Text(if (loggedIn) "خروج" else "ورود با ایمیل") }
            }
            Text(
                if (loggedIn)
                    "ویس‌ها پس از اتصال اینترنت خودکار تحلیل می‌شوند؛ قبل از ثبت نتیجه را بررسی کن."
                else "ویس حتی بدون اینترنت ذخیره می‌شود. برای پردازش خودکار با ایمیل وارد شو.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            if (loggedIn) {
                Text("حساب: " + auth.email,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall)
            }
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
                    upload?.status == "uploading" -> "در حال تحلیل و ارسال..."
                    upload?.status == "retry" -> "ارسال دوباره پس از اتصال / رفع خطای سرور"
                    upload?.status == "failed" -> "ارسال ناموفق"
                    !loggedIn -> "ذخیره روی گوشی؛ منتظر ورود"
                    else -> "در صف ارسال خودکار"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("ویس " + (index + 1), fontWeight = FontWeight.SemiBold)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        if (upload?.status == "failed" && upload.lastError.isNotBlank()) {
                            Text(upload.lastError, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (existing != null && !existing.applied) {
                        Button(onClick = { review = existing }) { Text("بازبینی") }
                    } else if (upload?.status == "failed") {
                        OutlinedButton(onClick = {
                            if (loggedIn) VoiceQueue.retry(context, path)
                            else showLogin = true
                            refreshTick++
                        }) { Text("تلاش مجدد") }
                    } else if (!loggedIn) {
                        OutlinedButton(onClick = { showLogin = true }) { Text("ورود") }
                    }
                }
                HorizontalDivider()
            }
            if (errorText.isNotBlank()) {
                Text(errorText, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLogin) {
        var email by remember { mutableStateOf(auth.email) }
        var password by remember { mutableStateOf("") }
        var registering by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!loginBusy) showLogin = false },
            title = { Text(if (registering) "ساخت حساب پویارا" else "ورود به پویارا") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ورود روی این گوشی به‌طور امن حفظ می‌شود تا ویس‌های آفلاین خودکار ارسال شوند.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = email, onValueChange = { email = it },
                        label = { Text("ایمیل") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("رمز عبور") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { registering = !registering }) {
                        Text(if (registering) "حساب دارم؛ ورود" else "حساب ندارم؛ ثبت نام")
                    }
                    if (loginError.isNotBlank()) {
                        Text(loginError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(enabled = !loginBusy, onClick = {
                    loginBusy = true
                    loginError = ""
                    scope.launch {
                        try {
                            val success = withContext(Dispatchers.IO) {
                                if (registering) auth.signUp(email, password)
                                else { auth.signIn(email, password); true }
                            }
                            if (success) {
                                loggedIn = auth.hasSession
                                showLogin = false
                                VoiceQueue.schedulePending(context)
                            } else {
                                loginError = "ایمیل تأیید حساب را بررسی کن؛ سپس وارد شو."
                            }
                        } catch (error: Exception) {
                            loginError = error.localizedMessage ?: "خطا در ورود"
                        } finally { loginBusy = false }
                    }
                }) { Text(if (loginBusy) "در حال اتصال..." else if (registering) "ثبت‌نام" else "ورود") }
            },
            dismissButton = {
                TextButton(enabled = !loginBusy, onClick = { showLogin = false }) {
                    Text("انصراف")
                }
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
