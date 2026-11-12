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
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val draftPrefs = remember { context.getSharedPreferences("capture_draft", Context.MODE_PRIVATE) }
    var recordId by rememberSaveable { mutableLongStateOf(startupEntry?.id ?: 0L) }
    var personName by rememberSaveable {
        mutableStateOf(startupEntry?.name ?: if (initialRecordId == 0L) draftPrefs.getString("name", "").orEmpty() else "")
    }
    var companyName by rememberSaveable {
        mutableStateOf(startupEntry?.company ?: if (initialRecordId == 0L) draftPrefs.getString("company", "").orEmpty() else "")
    }
    var selectedGroupId by rememberSaveable {
        mutableLongStateOf(startupEntry?.groupId ?: if (initialRecordId == 0L) draftPrefs.getLong("group_id", 0L) else 0L)
    }
    var groupSearch by rememberSaveable { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(emptySet<Long>()) }
    var draftAttachments by remember {
        mutableStateOf((draftPrefs.getStringSet("attachments", emptySet()) ?: emptySet()).toSet())
    }
    var draftLatitude by remember {
        mutableStateOf(draftPrefs.getString("draft_lat", null)?.toDoubleOrNull())
    }
    var draftLongitude by remember {
        mutableStateOf(draftPrefs.getString("draft_lon", null)?.toDoubleOrNull())
    }
    var scanBusy by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }
    var pendingScanPhoto by remember { mutableStateOf<File?>(null) }
    var scanPersonName by remember { mutableStateOf("") }
    var scanCompany by remember { mutableStateOf("") }
    var scanPhone by remember { mutableStateOf("") }
    var scanAddress by remember { mutableStateOf("") }
    var scanEmail by remember { mutableStateOf("") }
    var scanWebsite by remember { mutableStateOf("") }
    var scanRawText by remember { mutableStateOf("") }
    var phone by rememberSaveable {
        mutableStateOf(startupEntry?.phone ?: if (initialRecordId == 0L) draftPrefs.getString("phone", "").orEmpty() else "")
    }
    var note by rememberSaveable {
        mutableStateOf(startupEntry?.note ?: if (initialRecordId == 0L) draftPrefs.getString("note", "").orEmpty() else "")
    }
    var chosenTags by remember {
        mutableStateOf(startupEntry?.tags ?: if (initialRecordId == 0L)
            (draftPrefs.getStringSet("tags", emptySet()) ?: emptySet()).toSet() else emptySet())
    }
    var allEntries by remember { mutableStateOf(db.all()) }
    var fileRevision by remember { mutableIntStateOf(0) }
    var reminderRevision by remember { mutableIntStateOf(0) }
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
        ReminderScheduler.createChannel(context)
        VoiceQueue.schedulePending(context)
        callerEnabled = if (Build.VERSION.SDK_INT >= 29) {
            roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else false
    }

    fun refresh() {
        allEntries = db.all()
        fileRevision++
        reminderRevision++
    }

    fun persistDraft(
        newName: String = personName,
        newPhone: String = phone,
        newNote: String = note,
        newTags: Set<String> = chosenTags,
        newCompany: String = companyName,
        newGroupId: Long = selectedGroupId
    ) {
        if (recordId > 0L) return
        val editor = draftPrefs.edit()
            .putString("name", newName)
            .putString("company", newCompany)
            .putLong("group_id", newGroupId)
            .putString("phone", newPhone)
            .putString("note", newNote)
            .putStringSet("tags", newTags.toSet())
            .putStringSet("attachments", draftAttachments.toSet())
        if (draftLatitude != null && draftLongitude != null) {
            editor.putString("draft_lat", draftLatitude.toString())
                .putString("draft_lon", draftLongitude.toString())
        }
        editor.apply()
    }

    fun clearDraft() {
        draftPrefs.edit().clear().apply()
        draftAttachments = emptySet()
        draftLatitude = null
        draftLongitude = null
    }

    fun addDraftAttachment(kind: String, file: File) {
        draftAttachments = draftAttachments + "${kind}|${file.absolutePath}"
        persistDraft()
    }

    fun attachDraftData(id: Long) {
        draftAttachments.forEach { encoded ->
            val parts = encoded.split("|", limit = 2)
            if (parts.size == 2 && File(parts[1]).isFile) {
                db.addAttachment(id, parts[0], parts[1])
                if (parts[0] == "audio") db.linkVoiceUploadToEntry(parts[1], id)
            }
        }
        if (draftLatitude != null && draftLongitude != null) {
            db.setLocation(id, draftLatitude!!, draftLongitude!!, "manual")
        }
    }

    fun save(
        newName: String = personName,
        newPhone: String = phone,
        newNote: String = note,
        newTags: Set<String> = chosenTags,
        newCompany: String = companyName,
        newGroupId: Long = selectedGroupId
    ): Long {
        val tags = newTags + inferredTags(newNote)
        if (recordId == 0L && (newName.isBlank() || newPhone.isBlank())) {
            persistDraft(newName, newPhone, newNote, tags, newCompany, newGroupId)
            return 0L
        }
        val wasNew = recordId == 0L
        val id = db.save(
            recordId, newName, newPhone, newNote, tags,
            newCompany, newGroupId.takeIf { it > 0L }
        )
        recordId = id
        chosenTags = tags
        if (newGroupId <= 0L) {
            db.all().firstOrNull { it.id == id }?.company
                ?.takeIf { it.isNotBlank() }?.let { db.updateGroupCompany(id, it) }
        }
        if (wasNew) attachDraftData(id)
        if (wasNew) clearDraft()
        refresh()
        return id
    }

    fun ensureRecord(): Long {
        if (recordId == 0L) {
            if (personName.isBlank() || phone.isBlank()) {
                Toast.makeText(context, "برای ثبت پرونده، نام و شماره تماس را وارد کن", Toast.LENGTH_SHORT).show()
                return 0L
            }
            val tags = chosenTags + inferredTags(note)
            recordId = db.save(
                0L, personName, phone, note, tags, companyName,
                selectedGroupId.takeIf { it > 0L }
            )
            chosenTags = tags
            db.all().firstOrNull { it.id == recordId }?.company
                ?.takeIf { it.isNotBlank() }?.let { db.updateGroupCompany(recordId, it) }
            attachDraftData(recordId)
            clearDraft()
            refresh()
        }
        return recordId
    }

    fun open(entry: ContactEntry) {
        recordId = entry.id
        personName = entry.name
        companyName = entry.company
        selectedGroupId = entry.groupId ?: 0L
        groupSearch = ""
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
        clearDraft()
        recordId = 0L
        personName = ""
        companyName = ""
        selectedGroupId = 0L
        groupSearch = ""
        phone = ""
        note = ""
        chosenTags = emptySet()
        additionalNotes = emptyList()
        noteDraft = ""
        editingNoteId = 0L
        smsBody = ""
        page = "capture"
        fileRevision++
        reminderRevision++
    }

    fun goToMessages() {
        if (recordId == 0L) ensureRecord()
        if (recordId > 0L) {
            smsBody = db.smsDraft(recordId)
            page = "messages"
        } else {
            Toast.makeText(context, "ابتدا نام و شماره تماس را وارد کن", Toast.LENGTH_SHORT).show()
        }
    }

    fun callNumber(value: String) {
        if (!openDialer(context, value)) {
            Toast.makeText(context, "برنامه شماره‌گیر پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    fun beginRecording() {
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val owner = if (recordId > 0L) recordId.toString() else "draft"
        val target = File(folder, "voice_${owner}_${System.currentTimeMillis()}.m4a")
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
            if (recordId > 0L) {
                db.addAttachment(recordId, "audio", target.absolutePath)
                refresh()
            } else {
                addDraftAttachment("audio", target)
            }
            // Queue the preserved M4A immediately; WorkManager waits for internet.
            VoiceQueue.record(context, target, recordId)
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
            if (recordId > 0L) {
                db.addAttachment(recordId, "photo", file.absolutePath)
                refresh()
            } else {
                addDraftAttachment("photo", file)
            }
        } else {
            file?.delete()
        }
        pendingPhoto = null
    }

    val scanCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val file = pendingScanPhoto
        if (captured && file != null) {
            scanBusy = true
            Thread {
                val result = runCatching { CardOcr.recognize(context.applicationContext, file) }
                Handler(Looper.getMainLooper()).post {
                    scanBusy = false
                    result.onSuccess { card ->
                        scanPersonName = card.personName
                        scanCompany = card.company
                        scanPhone = card.phone
                        scanAddress = card.address
                        scanEmail = card.email
                        scanWebsite = card.website
                        scanRawText = card.rawText
                        showScanDialog = true
                    }.onFailure { error ->
                        file.delete()
                        pendingScanPhoto = null
                        Toast.makeText(
                            context,
                            "متن کارت تشخیص داده نشد: ${error.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        } else {
            file?.delete()
            pendingScanPhoto = null
        }
    }

    fun startCardScan() {
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(folder, "business_card_${System.currentTimeMillis()}.jpg")
        pendingScanPhoto = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        runCatching { scanCameraLauncher.launch(uri) }
            .onFailure {
                file.delete()
                pendingScanPhoto = null
                Toast.makeText(context, "دوربین در دسترس نیست", Toast.LENGTH_SHORT).show()
            }
    }

    fun takePhoto() {
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val owner = if (recordId > 0L) recordId.toString() else "draft"
        val file = File(folder, "photo_${owner}_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraLauncher.launch(uri)
    }

    fun saveAdditionalNote(text: String) {
        val id = ensureRecord()
        if (id == 0L) return
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

    val reminderNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (!allowed) Toast.makeText(context, "برای اعلان یادآوری، اجازه اعلان‌ها را فعال کن", Toast.LENGTH_LONG).show()
    }

    fun requestReminderNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) reminderNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun activateCallerContext() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestCallerRole()
    }

    val mapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK &&
            data?.hasExtra("latitude") == true && data.hasExtra("longitude")
        ) {
            draftLatitude = data.getDoubleExtra("latitude", 0.0)
            draftLongitude = data.getDoubleExtra("longitude", 0.0)
            if (recordId > 0L && draftLatitude != null && draftLongitude != null) {
                db.setLocation(recordId, draftLatitude!!, draftLongitude!!,
                    data.getStringExtra("location_source") ?: "manual")
                refresh()
            } else persistDraft()
        }
        refresh()
    }

    fun openMap(entryId: Long, allowPick: Boolean = false) {
        mapLauncher.launch(
            Intent(context, OfflineMapActivity::class.java)
                .putExtra("entry_id", entryId)
                .putExtra("allow_pick_location", allowPick || entryId > 0L)
        )
    }

    val files = remember(recordId, fileRevision) {
        if (recordId > 0) db.attachments(recordId) else emptyList()
    }

    Scaffold(
        containerColor = Color(0xFFF4F7F8),
        floatingActionButton = {
            val hasAnyInformation = personName.isNotBlank() ||
                companyName.isNotBlank() || phone.isNotBlank() || note.isNotBlank() ||
                draftAttachments.isNotEmpty() || draftLatitude != null || recordId > 0L
            if (page == "capture" && hasAnyInformation) {
                val canComplete = personName.isNotBlank() && phone.isNotBlank()
                FloatingActionButton(
                    modifier = Modifier.size(64.dp),
                    containerColor = if (canComplete) Color(0xFF087866) else Color(0xFFD9E5E3),
                    contentColor = if (canComplete) Color.White else Color(0xFF526C69),
                    shape = RoundedCornerShape(18.dp),
                    onClick = {
                        if (!canComplete) {
                            Toast.makeText(
                                context, "برای ثبت نهایی نام و شماره تماس را وارد کن",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (save() > 0L) {
                            Toast.makeText(context, "ثبت شد؛ مخاطب بعدی را وارد کن", Toast.LENGTH_SHORT).show()
                            newEntry()
                        }
                    }
                ) {
                    Text("✓", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
            }
        },
        // Material 3 resolves End to the physical left in the app's RTL layout.
        floatingActionButtonPosition = FabPosition.End,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                listOf(
                    Triple("capture", "ثبت سریع", "✎"),
                    Triple("list", "مخاطبان", "▦"),
                    Triple("agenda", "برنامه کاری", "▤"),
                    Triple("messages", "پیامک", "✉")
                ).forEach { (key, title, symbol) ->
                    NavigationBarItem(
                        selected = page == key,
                        onClick = {
                            when (key) {
                                "list" -> { refresh(); page = key }
                                "agenda" -> { refresh(); page = key }
                                "messages" -> goToMessages()
                                else -> page = key
                            }
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
        Box(Modifier.fillMaxWidth().height(54.dp)) {
            if (page == "capture") {
                Box(
                    Modifier.align(Alignment.CenterEnd)
                        .size(46.dp)
                        .background(Color(0xFFD8F3E9), RoundedCornerShape(12.dp))
                        .clickable(enabled = !scanBusy) { startCardScan() },
                    contentAlignment = Alignment.Center
                ) {
                    if (scanBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("▣", color = Color(0xFF08685F),
                        style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        if (page == "capture") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = personName,
                    onValueChange = {
                        personName = it
                        save(newName = it)
                    },
                    label = { Text("نام شخص") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = companyName,
                    onValueChange = {
                        companyName = it
                        save(newCompany = it)
                    },
                    label = { Text("نام مجموعه / برند") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = groupSearch,
                    onValueChange = { groupSearch = it },
                    label = { Text("اتصال به مجموعه یا مخاطب قبلی") },
                    placeholder = { Text("نام مدیر، برند یا مجموعه را جست‌وجو کن") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (selectedGroupId > 0L) {
                    val selectedRoot = allEntries.firstOrNull { it.id == selectedGroupId }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F3EF))) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("عضو این مجموعه", fontWeight = FontWeight.Bold)
                                Text(
                                    selectedRoot?.company?.ifBlank { selectedRoot.name }
                                        ?: "مخاطب مرتبط",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = {
                                selectedGroupId = 0L
                                save(newGroupId = 0L)
                            }) { Text("لغو اتصال") }
                        }
                    }
                } else if (groupSearch.isNotBlank()) {
                    val candidates = allEntries.filter { entry ->
                        listOf(entry.name, entry.company, entry.phone)
                            .any { it.contains(groupSearch, ignoreCase = true) }
                    }.take(5)
                    if (candidates.isEmpty()) {
                        Text("مخاطب یا مجموعه‌ای با این عبارت پیدا نشد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    } else {
                        candidates.forEach { candidate ->
                            TextButton(
                                onClick = {
                                    val rootId = candidate.groupId ?: candidate.id
                                    val root = allEntries.firstOrNull { it.id == rootId } ?: candidate
                                    selectedGroupId = rootId
                                    if (companyName.isBlank()) companyName = root.company
                                    groupSearch = ""
                                    save(newCompany = companyName, newGroupId = rootId)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    listOf(candidate.name, candidate.company, candidate.phone)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
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
                Text(
                    "ذخیره خودکار فعاله؛ تیک برای ثبت و شروع مخاطب بعدی است.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { callNumber(phone) },
                        enabled = personName.isNotBlank() && phone.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("☎ تماس") }
                    OutlinedButton(
                        onClick = { goToMessages() },
                        enabled = personName.isNotBlank() && phone.isNotBlank(),
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
                            else if (draftLatitude != null && draftLongitude != null)
                                "موقعیت موقت انتخاب شد: %.5f، %.5f".format(draftLatitude, draftLongitude)
                            else "هنوز موقعیت ثبت نشده؛ GPS یا لمس روی نقشه",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { openMap(recordId, allowPick = true) }
                        ) {
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

                VoiceAgentPanel(
                    db = db,
                    audioPaths = files.filter { it.type == "audio" }.map { it.filePath } +
                        draftAttachments.mapNotNull { saved ->
                            val parts = saved.split("|", limit = 2)
                            if (parts.size == 2 && parts[0] == "audio") parts[1] else null
                        },
                    // Never upload unrelated customer records to the AI service.
                    // Matching is performed locally after the transcript is returned.
                    contacts = allEntries.filter { it.id == recordId },
                    onImported = { refresh() }
                )

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
                ReminderComposer(
                    db = db,
                    entryId = recordId,
                    refreshKey = reminderRevision,
                    ensureContact = { ensureRecord() },
                    onSaved = { refresh() },
                    onRequestNotification = { requestReminderNotificationPermission() }
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
                        Text(personName.ifBlank { "مخاطب" },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(phone.ifBlank { "شماره ثبت نشده" },
                            style = MaterialTheme.typography.bodyLarge)
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
        } else if (page == "agenda") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WeeklyAgenda(
                    db = db,
                    allEntries = allEntries,
                    refreshKey = reminderRevision,
                    onOpenContact = { open(it) },
                    onChanged = { refresh() }
                )
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
                label = { Text("جست‌وجوی نام شخص، مجموعه، شماره یا برچسب") },
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
                    (search.isBlank() || listOf(entry.name, entry.phone, entry.note, entry.company, entry.tags.joinToString())
                        .any { it.contains(search, ignoreCase = true) } ||
                        db.notes(entry.id).any { it.text.contains(search, ignoreCase = true) })
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filtered.groupBy { it.groupId ?: it.id }.forEach { (groupRootId, members) ->
                    val groupRoot = allEntries.firstOrNull { it.id == groupRootId } ?: members.first()
                    val groupTitle = groupRoot.company.ifBlank { groupRoot.name }.ifBlank { "گروه مخاطبان" }
                    item(key = "group_${groupRootId}") {
                        Text(
                            "${groupTitle} · ${members.size} نفر",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    items(members, key = { it.id }) { entry ->
                        val related = allEntries.filter {
                            (it.groupId ?: it.id) == groupRootId && it.id != entry.id
                        }.sortedBy { it.name }
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { open(entry) },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (entry.name.isNotBlank()) Text(
                                    entry.name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (entry.company.isNotBlank()) Text(
                                    entry.company,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
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
                                if (related.isNotEmpty()) {
                                    TextButton(onClick = {
                                        expandedGroups = if (groupRootId in expandedGroups)
                                            expandedGroups - groupRootId else expandedGroups + groupRootId
                                    }) {
                                        Text(
                                            if (groupRootId in expandedGroups)
                                                "بستن افراد مرتبط (${related.size}) ▲"
                                            else "نمایش افراد مرتبط (${related.size}) ▼"
                                        )
                                    }
                                    if (groupRootId in expandedGroups) {
                                        related.forEach { person ->
                                            TextButton(
                                                onClick = { open(person) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    "${person.name.ifBlank { "بدون نام" }} · ${person.phone}",
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
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
        if (showScanDialog) {
            AlertDialog(
                onDismissRequest = {
                    pendingScanPhoto?.delete()
                    pendingScanPhoto = null
                    showScanDialog = false
                },
                title = { Text("اطلاعات خوانده‌شده از کارت") },
                text = {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 500.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("قبل از ثبت، موارد استخراج‌شده را بررسی و اصلاح کن.")
                        OutlinedTextField(
                            value = scanPersonName,
                            onValueChange = { scanPersonName = it },
                            label = { Text("نام شخص") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = scanCompany,
                            onValueChange = { scanCompany = it },
                            label = { Text("نام مجموعه / برند") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = scanPhone,
                            onValueChange = { scanPhone = it },
                            label = { Text("شماره تماس") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = scanAddress,
                            onValueChange = { scanAddress = it },
                            label = { Text("آدرس") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = scanEmail,
                            onValueChange = { scanEmail = it },
                            label = { Text("ایمیل") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = scanWebsite,
                            onValueChange = { scanWebsite = it },
                            label = { Text("وب‌سایت") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("متن تشخیص‌داده‌شده", fontWeight = FontWeight.SemiBold)
                        Text(scanRawText, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        personName = scanPersonName.trim()
                        companyName = scanCompany.trim()
                        phone = scanPhone.trim()
                        val extracted = buildList {
                            if (scanAddress.isNotBlank()) add("آدرس: ${scanAddress.trim()}")
                            if (scanEmail.isNotBlank()) add("ایمیل: ${scanEmail.trim()}")
                            if (scanWebsite.isNotBlank()) add("وب‌سایت: ${scanWebsite.trim()}")
                            if (scanRawText.isNotBlank()) add("متن کارت: ${scanRawText.trim()}")
                        }.joinToString("\n")
                        note = listOf(note.trim(), extracted).filter { it.isNotBlank() }
                            .joinToString("\n\n")
                        val savedId = save(
                            newName = personName,
                            newPhone = phone,
                            newNote = note,
                            newCompany = companyName
                        )
                        pendingScanPhoto?.takeIf { it.isFile }?.let { image ->
                            if (savedId > 0L) {
                                db.addAttachment(savedId, "photo", image.absolutePath)
                                refresh()
                            } else addDraftAttachment("photo", image)
                        }
                        pendingScanPhoto = null
                        showScanDialog = false
                    }) { Text("استفاده از اطلاعات") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingScanPhoto?.delete()
                        pendingScanPhoto = null
                        showScanDialog = false
                    }) { Text("انصراف") }
                }
            )
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
