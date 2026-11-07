package com.poyara.crm

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private val tagChoices = listOf("مبلمان", "کابینت", "معماری", "پیگیری", "مشتری ثابت", "مهم")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF08685F),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD8F3E9),
                    secondary = Color(0xFF4F6573),
                    background = Color(0xFFF4F7F8),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFE9EFF0),
                    onSurface = Color(0xFF1C3038),
                    outline = Color(0xFFB5C4C6)
                ),
                shapes = Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(26.dp)
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    CrmHome(intent?.getLongExtra("record_id", 0L) ?: 0L)
                }
            }
        }
    }
}

private fun inferredTags(text: String): Set<String> = buildSet {
    if ("مبل" in text) add("مبلمان")
    if ("کابینت" in text) add("کابینت")
    if ("معمار" in text) add("معماری")
    if ("پیگیری" in text || "زنگ بزنم" in text || "تماس بگیرم" in text) add("پیگیری")
}

@Composable
private fun CrmHome(initialRecordId: Long) {
    val context = LocalContext.current
    val db = remember { CrmDb(context.applicationContext) }
    var page by rememberSaveable { mutableStateOf("capture") }
    val startupEntry = remember(initialRecordId) { db.all().find { it.id == initialRecordId } }
    var recordId by rememberSaveable { mutableLongStateOf(startupEntry?.id ?: 0L) }
    var phone by rememberSaveable { mutableStateOf(startupEntry?.phone.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(startupEntry?.note.orEmpty()) }
    var chosenTags by remember { mutableStateOf(startupEntry?.tags ?: emptySet()) }
    var allEntries by remember { mutableStateOf(db.all()) }
    var fileRevision by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var filterTag by rememberSaveable { mutableStateOf("همه") }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var additionalNotes by remember { mutableStateOf(startupEntry?.let { db.notes(it.id) } ?: emptyList()) }
    var noteDraft by remember { mutableStateOf("") }
    var editingNoteId by remember { mutableLongStateOf(0L) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var photoPreview by remember { mutableStateOf<String?>(null) }
    val backup = remember { CrmBackup(context.applicationContext, db) }
    var smsBody by rememberSaveable { mutableStateOf(startupEntry?.let { db.smsDraft(it.id) } ?: "") }
    var callerEnabled by remember { mutableStateOf(false) }
    val roleManager = remember {
        if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
    }
    LaunchedEffect(Unit) {
        callerEnabled = if (Build.VERSION.SDK_INT >= 29) {
            roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else false
    }

    fun refresh() {
        allEntries = db.all()
        fileRevision++
    }

    fun save(
        newPhone: String = phone,
        newNote: String = note,
        newTags: Set<String> = chosenTags
    ): Long {
        val tags = newTags + inferredTags(newNote)
        if (recordId == 0L && newPhone.isBlank() && newNote.isBlank() && tags.isEmpty()) return 0L
        val id = db.save(recordId, newPhone, newNote, tags)
        recordId = id
        chosenTags = tags
        refresh()
        return id
    }

    fun ensureRecord(): Long {
        if (recordId == 0L) {
            recordId = db.save(0L, phone, note, chosenTags)
            refresh()
        }
        return recordId
    }

    fun open(entry: ContactEntry) {
        recordId = entry.id
        phone = entry.phone
        note = entry.note
        chosenTags = entry.tags
        additionalNotes = db.notes(entry.id)
        noteDraft = ""
        editingNoteId = 0L
        smsBody = db.smsDraft(entry.id)
        page = "capture"
        fileRevision++
    }

    fun newEntry() {
        recordId = 0L
        phone = ""
        note = ""
        chosenTags = emptySet()
        additionalNotes = emptyList()
        noteDraft = ""
        editingNoteId = 0L
        smsBody = ""
        page = "capture"
        fileRevision++
    }

    fun goToMessages() {
        if (recordId == 0L && (phone.isNotBlank() || note.isNotBlank())) ensureRecord()
        if (recordId > 0L) {
            smsBody = db.smsDraft(recordId)
            page = "messages"
        } else {
            page = "list"
            Toast.makeText(context, "ابتدا مخاطب را انتخاب کن", Toast.LENGTH_SHORT).show()
        }
    }

    fun callNumber(value: String) {
        if (!openDialer(context, value)) {
            Toast.makeText(context, "برنامه شماره‌گیر پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    fun beginRecording() {
        val id = ensureRecord()
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val target = File(folder, "voice_${id}_${System.currentTimeMillis()}.m4a")
        try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            audioFile = target
            isRecording = true
        } catch (error: Exception) {
            recorder?.release()
            recorder = null
            target.delete()
            Toast.makeText(context, "ضبط شروع نشد: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecording() {
        val r = recorder ?: return
        val target = audioFile
        var succeeded = false
        try {
            r.stop()
            succeeded = true
        } catch (_: Exception) {
            target?.delete()
        } finally {
            r.release()
            recorder = null
            audioFile = null
            isRecording = false
        }
        if (succeeded && target != null) {
            db.addAttachment(ensureRecord(), "audio", target.absolutePath)
            refresh()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) beginRecording()
        else Toast.makeText(context, "برای ضبط صدا دسترسی میکروفون لازم است.", Toast.LENGTH_SHORT).show()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { photographed ->
        val file = pendingPhoto
        if (photographed && file != null) {
            db.addAttachment(ensureRecord(), "photo", file.absolutePath)
            refresh()
        } else {
            file?.delete()
        }
        pendingPhoto = null
    }

    fun takePhoto() {
        val id = ensureRecord()
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(folder, "photo_${id}_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraLauncher.launch(uri)
    }

    fun saveAdditionalNote(text: String) {
        val id = ensureRecord()
        editingNoteId = db.saveNote(editingNoteId, id, text)
        additionalNotes = db.notes(id)
    }

    fun toggleAudio(path: String) {
        audioPlayer?.release()
        audioPlayer = null
        if (playingPath == path) {
            playingPath = null
            return
        }
        try {
            val player = MediaPlayer()
            player.setDataSource(path)
            player.prepare()
            player.setOnCompletionListener {
                it.release()
                audioPlayer = null
                playingPath = null
            }
            player.start()
            audioPlayer = player
            playingPath = path
        } catch (error: Exception) {
            playingPath = null
            Toast.makeText(context, "فایل صوتی باز نشد", Toast.LENGTH_SHORT).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            runCatching { backup.exportTo(uri) }
                .onSuccess { Toast.makeText(context, "پشتیبان $it پرونده ساخته شد", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, "خطا در پشتیبان‌گیری: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { backup.importFrom(uri) }
                .onSuccess {
                    refresh()
                    Toast.makeText(context, "$it پرونده جدید بازیابی شد", Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(context, "خطا در بازیابی: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        callerEnabled = if (Build.VERSION.SDK_INT >= 29) {
            roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else false
    }

    fun requestCallerRole() {
        if (Build.VERSION.SDK_INT >= 29 &&
            roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
        ) {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        } else {
            Toast.makeText(context, "شناسایی تماس در این گوشی پشتیبانی نشد", Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) requestCallerRole()
        else Toast.makeText(context, "نمایش جزئیات تماس به اجازه اعلان نیاز دارد", Toast.LENGTH_LONG).show()
    }

    fun activateCallerContext() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestCallerRole()
    }

    val mapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh()
    }

    fun openMap(entryId: Long) {
        mapLauncher.launch(
            Intent(context, OfflineMapActivity::class.java)
                .putExtra("entry_id", entryId)
        )
    }

    val files = remember(recordId, fileRevision) {
        if (recordId > 0) db.attachments(recordId) else emptyList()
    }

    Scaffold(
        containerColor = Color(0xFFF4F7F8),
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                listOf(
                    Triple("capture", "ثبت سریع", "✎"),
                    Triple("list", "مخاطبان", "▦"),
                    Triple("messages", "پیامک", "✉")
                ).forEach { (key, title, symbol) ->
                    NavigationBarItem(
                        selected = page == key,
                        onClick = {
                            if (key == "list") { refresh(); page = key }
                            else if (key == "messages") goToMessages()
                            else page = key
                        },
                        icon = { Text(symbol, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(title, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }
    ) { insets ->
    Column(
        Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF0B7569), Color(0xFF123D48))),
                    RoundedCornerShape(24.dp)
                ).padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("POYARA", color = Color(0xFFAEF3DF),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when (page) {
                            "list" -> "شبکه ارتباطات کاری"
                            "messages" -> "مرکز پیامک"
                            else -> "ثبت فوری اطلاعات"
                        },
                        color = Color.White, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                if (page == "capture") {
                    TextButton(onClick = { newEntry() }) {
                        Text("+ جدید", color = Color.White)
                    }
                } else {
                    Text("${allEntries.size} مخاطب", color = Color(0xFFD2EEE7),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (page == "capture") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("بدون دکمه ذخیره؛ هر تغییر همان لحظه ثبت می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        save(newPhone = it)
                    },
                    label = { Text("شماره تماس") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { callNumber(phone) },
                        enabled = phone.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("☎ تماس") }
                    OutlinedButton(
                        onClick = { goToMessages() },
                        enabled = phone.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("✉ پیامک") }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        note = it
                        save(newNote = it)
                    },
                    label = { Text("هرچه درباره مخاطب می‌دانی") },
                    placeholder = { Text("رضایی، مالک مبلمان آریا؛ دموی چیدو را دید، هفته بعد تماس بگیرم...") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F3EF))) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val current = allEntries.firstOrNull { it.id == recordId }
                        Text("موقعیت مشتری", fontWeight = FontWeight.Bold)
                        Text(
                            if (current?.latitude != null && current.longitude != null)
                                "موقعیت ثبت شده: %.5f، %.5f".format(
                                    current.latitude, current.longitude
                                )
                            else "هنوز موقعیت ثبت نشده؛ GPS یا لمس روی نقشه",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { openMap(ensureRecord()) }) {
                            Text("⌖ ثبت روی نقشه یا GPS")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isRecording) stopRecording()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = if (isRecording) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) { Text(if (isRecording) "■ پایان ضبط" else "● ضبط صوت") }
                    OutlinedButton(onClick = { takePhoto() }) { Text("📷 عکس فوری") }
                }

                if (files.isNotEmpty()) {
                    Text("پیوست‌ها (${files.size})", fontWeight = FontWeight.Bold)
                    files.forEachIndexed { index, attachment ->
                        OutlinedButton(
                            onClick = {
                                if (attachment.type == "audio") toggleAudio(attachment.filePath)
                                else photoPreview = attachment.filePath
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val label = if (attachment.type == "audio") {
                                if (playingPath == attachment.filePath) "■ توقف" else "▶ پخش"
                            } else "▣ نمایش عکس"
                            Text("${index + 1}. $label")
                        }
                    }
                }

                Text("یادداشت‌های تکمیلی", fontWeight = FontWeight.SemiBold)
                additionalNotes.forEachIndexed { index, item ->
                    Card(Modifier.fillMaxWidth().clickable {
                        editingNoteId = item.id
                        noteDraft = item.text
                    }) {
                        Text(
                            "${index + 1}. ${item.text.ifBlank { "یادداشت خالی" }}",
                            modifier = Modifier.padding(12.dp),
                            maxLines = 4
                        )
                    }
                }
                TextButton(onClick = {
                    editingNoteId = 0L
                    noteDraft = ""
                }) { Text("+ یادداشت جدید") }
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = {
                        noteDraft = it
                        saveAdditionalNote(it)
                    },
                    label = { Text(if (editingNoteId == 0L) "یادداشت تازه (ذخیره خودکار)" else "ویرایش یادداشت") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                if (recordId > 0L) {
                    val events = db.callEvents(recordId)
                    if (events.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("تماس‌های شناسایی‌شده", fontWeight = FontWeight.Bold)
                                events.take(5).forEach { event ->
                                    Text(event, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }

                Text("برچسب‌ها", fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tagChoices.forEach { tag ->
                        FilterChip(
                            selected = tag in chosenTags,
                            onClick = {
                                val next = if (tag in chosenTags) chosenTags - tag else chosenTags + tag
                                chosenTags = next
                                save(newTags = next)
                            },
                            label = { Text(tag) }
                        )
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE1F3EF))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("شناسایی تماس‌های کاری", fontWeight = FontWeight.Bold)
                        Text(
                            if (callerEnabled) "فعال است؛ در تماس‌های شناخته‌شده یادداشت به صورت اعلان نمایش داده می‌شود."
                            else "برای دیدن خلاصه پرونده هنگام تماس ورودی و خروجی، این گزینه را فعال کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!callerEnabled) {
                            TextButton(onClick = { activateCallerContext() }) {
                                Text("فعال‌سازی شناسایی تماس")
                            }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = {
                        val manager = context.getSystemService(StatusBarManager::class.java)
                        manager?.requestAddTileService(
                            ComponentName(context, QuickCaptureTileService::class.java),
                            "ثبت مخاطب",
                            Icon.createWithResource(context, android.R.drawable.ic_menu_add),
                            context.mainExecutor
                        ) { }
                    }) {
                        Text("افزودن میان‌بر به پنل بالای گوشی")
                    }
                } else {
                    Text(
                        "میان‌بر ثبت مخاطب را از ویرایش پنل تنظیمات سریع گوشی اضافه کن.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    "فایل‌ها و اطلاعات روی همین گوشی نگهداری می‌شوند. پردازش هوش مصنوعی در نسخه بعدی اضافه می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else if (page == "messages") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("مخاطب", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary)
                        Text(phone.ifBlank { "شماره ثبت نشده" },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val company = allEntries.find { it.id == recordId }?.company.orEmpty()
                        if (company.isNotBlank()) Text(company,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("متن‌های آماده", fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("پیگیری", "هماهنگی جلسه", "ارسال پیشنهاد", "تشکر").forEach { type ->
                        AssistChip(
                            onClick = {
                                val company = allEntries.find { it.id == recordId }?.company.orEmpty()
                                smsBody = smsTemplate(type, company)
                                if (recordId > 0) db.saveSmsDraft(recordId, smsBody)
                            },
                            label = { Text(type) }
                        )
                    }
                }
                OutlinedTextField(
                    value = smsBody,
                    onValueChange = {
                        smsBody = it
                        if (recordId > 0) db.saveSmsDraft(recordId, it)
                    },
                    label = { Text("متن پیامک (ذخیره خودکار)") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phone.isNotBlank() && smsBody.isNotBlank(),
                    onClick = {
                        val opened = openSmsComposer(context, phone, smsBody)
                        if (opened && recordId > 0) db.markSmsOpened(recordId, smsBody)
                        if (!opened) Toast.makeText(
                            context, "برنامه پیامک پیدا نشد", Toast.LENGTH_SHORT
                        ).show()
                    }
                ) { Text("باز کردن پیامک برای بررسی و ارسال") }
                Text(
                    "ارسال نهایی در برنامه پیامک گوشی انجام می‌شود. CRM فقط بازشدن صفحه ارسال را ثبت می‌کند، نه تحویل پیام.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text("پیام‌های آماده‌شده قبلی", fontWeight = FontWeight.Bold)
                if (recordId > 0) db.smsHandoffs(recordId).forEach { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("انتقال به برنامه پیامک", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text(item, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Button(
                onClick = { openMap(0L) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("⌖ نقشه مشتریان و افراد اطراف من") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exportLauncher.launch("poyara-backup.zip")
                }) { Text("پشتیبان ZIP") }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text("بازیابی پشتیبان") }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("جست‌وجوی شماره، شرکت، متن یا برچسب") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (listOf("همه") + tagChoices).forEach { tag ->
                    FilterChip(
                        selected = filterTag == tag,
                        onClick = { filterTag = tag },
                        label = { Text(tag) }
                    )
                }
            }
            val filtered = allEntries.filter { entry ->
                (filterTag == "همه" || filterTag in entry.tags) &&
                    (search.isBlank() || listOf(entry.phone, entry.note, entry.company, entry.tags.joinToString())
                        .any { it.contains(search, ignoreCase = true) } ||
                        db.notes(entry.id).any { it.text.contains(search, ignoreCase = true) })
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filtered.groupBy { it.company.ifBlank { "بدون نام شرکت" } }.forEach { (company, members) ->
                    item(key = "company_$company") {
                        Text(company, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    }
                    items(members, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { open(entry) },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(entry.phone.ifBlank { "بدون شماره" }, fontWeight = FontWeight.SemiBold)
                                if (entry.note.isNotBlank()) Text(
                                    entry.note,
                                    maxLines = 3,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (entry.tags.isNotEmpty()) Text(
                                    entry.tags.joinToString(" · "),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                HorizontalDivider(modifier = Modifier.padding(top = 7.dp, bottom = 3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TextButton(
                                        onClick = { callNumber(entry.phone) },
                                        enabled = entry.phone.isNotBlank()
                                    ) { Text("☎ تماس") }
                                    TextButton(
                                        onClick = {
                                            open(entry)
                                            goToMessages()
                                        },
                                        enabled = entry.phone.isNotBlank()
                                    ) { Text("✉ پیامک") }
                                    TextButton(onClick = { open(entry) }) { Text("پرونده") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (photoPreview != null) {
            Dialog(onDismissRequest = { photoPreview = null }) {
                Surface(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val bitmap = remember(photoPreview) {
                            BitmapFactory.decodeFile(
                                photoPreview,
                                BitmapFactory.Options().apply { inSampleSize = 2 }
                            )?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "عکس پیوست",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 550.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text("تصویر قابل نمایش نیست")
                        }
                        TextButton(onClick = { photoPreview = null }) { Text("بستن") }
                    }
                }
            }
        }

    }
    }
}
