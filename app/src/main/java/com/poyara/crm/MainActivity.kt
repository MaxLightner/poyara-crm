package com.poyara.crm

import android.Manifest
import android.app.role.RoleManager
import android.accounts.AccountManager
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
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val tagChoices = listOf("مبلمان", "کابینت", "معماری", "مدیریت", "پیگیری", "مشتری ثابت", "مهم")
private val categoryChoices = listOf("مبل‌فروشی", "مهندسی ساختمان", "کابینت‌سازی", "سایر")

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
    val coroutineScope = rememberCoroutineScope()
    val googlePrefs = remember { context.getSharedPreferences(GoogleContactsBridge.PREFS, Context.MODE_PRIVATE) }
    var googleAccount by remember { mutableStateOf(googlePrefs.getString("account", "").orEmpty()) }
    var googleSyncBusy by remember { mutableStateOf(false) }
    val textAgentSettings = remember { VoiceServerSettings(context.applicationContext) }
    val agentPrefs = remember { context.getSharedPreferences("poyara_text_agent", Context.MODE_PRIVATE) }
    var agentPrivacyConsent by remember {
        mutableStateOf(agentPrefs.getBoolean("cloud_text_consent_v1", false))
    }
    // Every installation, including upgrades from a paid-agent build, must
    // explicitly consent before transcript text leaves the device.
    var agentOptIn by remember {
        mutableStateOf(agentPrefs.getBoolean("enabled", false) &&
            agentPrefs.getBoolean("cloud_text_consent_v1", false))
    }
    var agentUrl by rememberSaveable { mutableStateOf(textAgentSettings.endpoint) }
    var agentPairingToken by remember { mutableStateOf("") }
    var agentBusy by remember { mutableStateOf(false) }
    var pendingVoiceTranscript by rememberSaveable { mutableStateOf("") }
    var pendingVoiceFilePath by rememberSaveable { mutableStateOf("") }
    var voiceTranscribing by remember { mutableStateOf(false) }
    var voiceFileReader by remember { mutableStateOf<VoiceFileTranscriber?>(null) }
    var showExtraDetails by rememberSaveable { mutableStateOf(false) }
    var showMapDetails by rememberSaveable { mutableStateOf(false) }
    var showReminderDetails by rememberSaveable { mutableStateOf(false) }
    var showAttachments by rememberSaveable { mutableStateOf(false) }
    var showCallHistory by rememberSaveable { mutableStateOf(false) }
    var showAllGroups by rememberSaveable { mutableStateOf(false) }
    var showAllRoles by rememberSaveable { mutableStateOf(false) }
    var customCategories by remember {
        mutableStateOf(context.getSharedPreferences("crm_categories", Context.MODE_PRIVATE)
            .getStringSet("names", emptySet()).orEmpty().toSet())
    }
    var newCategoryName by rememberSaveable { mutableStateOf("") }
    var addCategoryDialog by remember { mutableStateOf(false) }
    var agentMessages by remember { mutableStateOf<List<String>>(emptyList()) }
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
    var extraPhones by remember { mutableStateOf(startupEntry?.extraPhones ?: draftPrefs.getString("extra_phones", "").orEmpty().split("|").filter { it.isNotBlank() }) }
    var contactRole by rememberSaveable { mutableStateOf(startupEntry?.role ?: draftPrefs.getString("role", "").orEmpty()) }
    var contactCategory by rememberSaveable { mutableStateOf(startupEntry?.category ?: draftPrefs.getString("category", "").orEmpty()) }
    var showExtraPhones by remember { mutableStateOf(extraPhones.isNotEmpty()) }
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
    var filterRole by rememberSaveable { mutableStateOf("همه سمت‌ها") }
    var filterCategory by rememberSaveable { mutableStateOf("همه گروه‌ها") }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var recordingClock by remember { mutableLongStateOf(0L) }
    var recordingError by remember { mutableStateOf("") }
    // Default to Google's built-in live microphone recognizer. Text is hidden
    // throughout dictation and only appears when Stop is tapped.
    var googleVoiceMode by rememberSaveable { mutableStateOf(true) }
    var googleVoiceBusy by remember { mutableStateOf(false) }
    var googleVoiceStage by remember { mutableStateOf("") }
    var googleSession by remember { mutableStateOf<GoogleDictationSession?>(null) }
    var pendingAutoTranscriptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastVoiceActions by remember { mutableStateOf<List<String>>(emptyList()) }
    var needVoiceNotificationPermission by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            googleSession?.cancel()
            voiceFileReader?.cancel()
        }
    }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            recordingClock = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
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
        if (googleAccount.isNotBlank()) PoyaraGoogleSyncWorker.schedule(context)
        // Google file-to-text mode: stop leftover background uploads from old versions.
        // Voice files and all CRM records are retained.
        androidx.work.WorkManager.getInstance(context)
            .cancelAllWorkByTag("poyara_voice_upload")
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
            .putString("extra_phones", extraPhones.joinToString("|"))
            .putString("role", contactRole)
            .putString("category", contactCategory)
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
        if (recordId == 0L && newNote.isBlank() && newName.isBlank() &&
            newCompany.isBlank() && newPhone.isBlank()) {
            persistDraft(newName, newPhone, newNote, tags, newCompany, newGroupId)
            return 0L
        }
        val wasNew = recordId == 0L
        val id = db.save(
            recordId, newName, newPhone, newNote, tags,
            newCompany, newGroupId.takeIf { it > 0L },
            extraPhones, contactRole, contactCategory
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

    fun appendRawSpeech(transcript: String) {
        val value = transcript.trim()
        if (value.isBlank()) return
        // User first reviews raw speech; no CRM commands run until Process is tapped.
        val next = listOf(note.trim(), value).filter { it.isNotBlank() }
            .joinToString("\n\n")
        note = next
        pendingVoiceTranscript = listOf(pendingVoiceTranscript, value)
            .filter { it.isNotBlank() }.joinToString("\n")
        save(newNote = next)
        agentMessages = listOf("گفتار به کادر اضافه شد. برای اجرای دستورها، «پردازش» را بزن.")
    }

    // Voice input is the user's source of truth. Keep the original transcript,
    // apply only deterministic, high-confidence CRM commands, and preserve older
    // values unless the speaker explicitly says a correction.
    fun addSpeechToCapture(transcript: String) {
        if (transcript.isBlank() && pendingVoiceFilePath.isBlank()) return
        val actions = PersianVoiceInterpreter.parse(
            if (agentOptIn && textAgentSettings.configured) "" else transcript,
            personName, companyName, phone)
        val previousName = personName
        val previousCompany = companyName
        val previousPhone = phone
        val updatedName = when {
            actions.correctPerson && actions.person != null -> actions.person
            personName.isBlank() && actions.person != null -> actions.person
            else -> personName
        }
        val updatedCompany = when {
            actions.correctCompany && actions.company != null -> actions.company
            companyName.isBlank() && actions.company != null -> actions.company
            else -> companyName
        }
        val updatedPhone = when {
            actions.correctPhone && actions.phone != null -> actions.phone
            phone.isBlank() && actions.phone != null -> actions.phone
            else -> phone
        }
        val feedback = mutableListOf<String>()
        val warnings = actions.alerts.toMutableList()
        if ((contactRole.isBlank() || actions.correctPerson) && !actions.role.isNullOrBlank()) {
            contactRole = actions.role
            feedback += "سمت: " + contactRole
        }
        if (contactCategory.isBlank() && !actions.category.isNullOrBlank()) {
            contactCategory = actions.category
            feedback += "گروه: " + contactCategory
        }
        if (contactRole.contains("مدیر") || contactRole.contains("مدیریت")) {
            chosenTags = chosenTags + "مدیریت"
        }
        if (updatedName != previousName) feedback += "نام: " + updatedName
        if (updatedCompany != previousCompany) feedback += "مجموعه: " + updatedCompany
        if (updatedPhone != previousPhone) feedback += "شماره: " + updatedPhone
        if (actions.correctCompany && actions.company == null)
            warnings += "نام صحیح مجموعه تشخیص داده نشد؛ مقدار قبلی تغییر نکرد."
        if (actions.correctPerson && actions.person == null)
            warnings += "نام صحیح شخص تشخیص داده نشد؛ مقدار قبلی تغییر نکرد."

        personName = updatedName
        companyName = updatedCompany
        phone = updatedPhone

        // Save the spoken note first so a valid reminder can be linked to this
        // exact profile. Note-only profiles are allowed, no name/phone required.
        val provisional = VoiceCaptureDocument.merge(
            note, transcript, personName, companyName, phone, emptyList(), warnings)
        note = provisional
        val entryId = save(newName = personName, newCompany = companyName,
            newPhone = phone, newNote = provisional)

        // If an explicit name/company correction changes the contact label,
        // keep this contact's existing reminder titles consistent as well.
        if (entryId > 0L && (actions.correctPerson || actions.correctCompany)) {
            val previousLabel = previousName.ifBlank { previousCompany }
            val updatedLabel = updatedName.ifBlank { updatedCompany }
            if (previousLabel.isNotBlank() && updatedLabel.isNotBlank() &&
                previousLabel != updatedLabel) {
                db.allReminders().filter {
                    it.entryId == entryId && it.title.contains(" با " + previousLabel)
                }.forEach { previous ->
                    if (db.updateReminder(previous.id, entryId,
                            previous.title.replace(" با " + previousLabel, " با " + updatedLabel),
                            previous.dueAt, previous.remindDaysBefore)) {
                        ReminderScheduler.cancel(context, previous.id)
                        db.allReminders().firstOrNull { it.id == previous.id }
                            ?.let { ReminderScheduler.schedule(context, it) }
                    }
                }
            }
        }

        val appointment = actions.appointment
        if (appointment != null && entryId > 0L) {
            try {
                val matching = db.allReminders().filter {
                    it.entryId == entryId &&
                        if (appointment.category == "جلسه")
                            it.title.contains("جلسه") else
                            (it.title.contains("تماس") || it.title.contains("پیگیری"))
                }
                if (appointment.correction) {
                    if (matching.size != 1) {
                        warnings += "برای اصلاح زمان، باید دقیقاً یک قرار از این نوع در پرونده باشد."
                    } else if (appointment.warning?.contains("صبح است یا عصر") == true) {
                        warnings += appointment.warning
                    } else {
                        val previous = matching.single()
                        val updatedDue = appointment.dueAt ?: if (
                            appointment.minuteOfDay != null &&
                            appointment.warning?.startsWith("روز ") == true
                        ) {
                            val zone = java.time.ZoneId.systemDefault()
                            val date = java.time.Instant.ofEpochMilli(previous.dueAt)
                                .atZone(zone).toLocalDate()
                            date.atStartOfDay(zone).toInstant().toEpochMilli() +
                                appointment.minuteOfDay * 60_000L
                        } else null
                        if (updatedDue == null || updatedDue <= System.currentTimeMillis()) {
                            warnings += "زمان جدید قرار واضح یا معتبر نبود؛ قرار قبلی حفظ شد."
                        } else {
                            val changedLead = Regex("""[0-7]\s*روز\s*قبل|(?:صفر|یک|دو|سه|چهار|پنج|شش|هفت)\s*روز\s*قبل""")
                                .containsMatchIn(PersianVoiceInterpreter.normalize(transcript))
                            val lead = if (changedLead) appointment.leadDays else previous.remindDaysBefore
                            if (db.updateReminder(previous.id, entryId, previous.title,
                                    updatedDue, lead)) {
                                ReminderScheduler.cancel(context, previous.id)
                                db.allReminders().firstOrNull { it.id == previous.id }
                                    ?.let { ReminderScheduler.schedule(context, it) }
                                needVoiceNotificationPermission = true
                                feedback += "زمان " + appointment.category + " اصلاح شد."
                            } else warnings += "اصلاح قرار ذخیره نشد؛ از برنامه کاری بررسی کن."
                        }
                    }
                } else if (appointment.dueAt == null) {
                    appointment.warning?.let { warnings += it }
                } else if (matching.any {
                        it.dueAt == appointment.dueAt &&
                            it.remindDaysBefore == appointment.leadDays
                    }) {
                    feedback += "این " + appointment.category + " قبلاً در برنامه کاری ثبت شده است."
                } else {
                    val target = listOf(personName, companyName).firstOrNull { it.isNotBlank() }
                    val title = appointment.category +
                        if (target == null) "" else " با " + target
                    val reminderId = db.addReminder(
                        entryId, title, appointment.dueAt, appointment.leadDays)
                    val reminder = db.allReminders().first { it.id == reminderId }
                    ReminderScheduler.createChannel(context)
                    ReminderScheduler.schedule(context, reminder)
                    needVoiceNotificationPermission = true
                    val alertAt = reminder.dueAt -
                        java.util.concurrent.TimeUnit.DAYS.toMillis(reminder.remindDaysBefore.toLong())
                    feedback += appointment.category + " در برنامه کاری ثبت شد."
                    if (alertAt <= System.currentTimeMillis()) {
                        warnings += "موعد یادآوری " + reminder.remindDaysBefore +
                            " روز قبل گذشته است؛ سیستم یک اعلان نزدیک‌تر تنظیم می‌کند."
                    } else {
                        feedback += "اعلان " + reminder.remindDaysBefore + " روز قبل تنظیم شد."
                    }
                }
            } catch (error: Exception) {
                warnings += "ثبت یا اصلاح قرار ناموفق بود؛ اطلاعات گفتار محفوظ است."
            }
        }
        val upcoming = db.allReminders().filter { it.entryId == entryId }
            .sortedBy { it.dueAt }.take(4).map {
                val date = java.text.SimpleDateFormat(
                    "EEE dd MMM · HH:mm", java.util.Locale("fa", "IR")
                ).format(java.util.Date(it.dueAt))
                it.title + " · " + date + " · " + it.remindDaysBefore + " روز قبل"
            }
        val revised = VoiceCaptureDocument.merge(
            note, transcript, personName, companyName, phone, upcoming, warnings)
        note = revised
        save(newName = personName, newCompany = companyName,
            newPhone = phone, newNote = revised)
        lastVoiceActions = (feedback + warnings).distinct().ifEmpty {
            listOf("متن گفتار ذخیره شد؛ دستور روشن دیگری برای اعمال پیدا نشد.")
        }
        if (agentOptIn && agentPrivacyConsent && textAgentSettings.configured && !agentBusy) {
            val sourceEntryId = entryId
            val sourceName = personName
            val sourcePhone = phone
            agentBusy = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { CrmTextAgentClient(context.applicationContext)
                        .let { client ->
                            val audio = pendingVoiceFilePath.takeIf { it.isNotBlank() }
                                ?.let { File(it) }?.takeIf { it.isFile }
                            val sameContact = db.all().filter { it.id == sourceEntryId }
                            if (audio != null)
                                client.analyzeAudio(audio, transcript, sameContact,
                                    db.allReminders().filter { it.entryId == sourceEntryId })
                            else client.analyze(transcript, sameContact)
                        } }
                }
                agentBusy = false
                result.onSuccess { json ->
                    if (sourceEntryId != recordId || sourceEntryId <= 0L) {
                        agentMessages = listOf("تحلیل Agent آماده است اما پرونده عوض شده؛ تغییری اعمال نشد.")
                    } else {
                        val notes = mutableListOf<String>()
                        val contacts = json.optJSONArray("contacts")
                        if (contacts != null && contacts.length() == 1) {
                            val contact = contacts.optJSONObject(0)
                            if (contact != null) {
                                fun clean(key: String): String = contact.optString(key)
                                    .takeIf { it != "null" }.orEmpty().trim()
                                val proposedName = clean("name")
                                // Prevent moving another person's facts into the current record.
                                val identityMatches = proposedName.isBlank() || sourceName.isBlank() ||
                                    proposedName == sourceName ||
                                    ("آقای " + proposedName) == sourceName ||
                                    ("خانم " + proposedName) == sourceName
                                if (identityMatches && sourcePhone == phone) {
                                    if (personName.isBlank() && proposedName.isNotBlank()) personName = proposedName
                                    if (companyName.isBlank() && clean("company").isNotBlank()) companyName = clean("company")
                                    val proposedNumber = normalizePhone(clean("phone"))
                                    if (phone.isBlank() && proposedNumber.matches(Regex("""0\d{9,10}"""))) phone = proposedNumber
                                    if (contactRole.isBlank() && clean("role").isNotBlank()) contactRole = clean("role")
                                    if (contactCategory.isBlank() && clean("category").isNotBlank()) contactCategory = clean("category")
                                    if (contactRole.contains("مدیر") || contactRole.contains("مدیریت"))
                                        chosenTags = chosenTags + "مدیریت"
                                    save()
                                    notes += "اطلاعات روشن مخاطب از Agent در پرونده اعمال شد."
                                } else notes += "پیشنهاد مخاطب دیگر تشخیص داده شد؛ برای جلوگیری از اشتباه اعمال نشد."
                            }
                        }
                        val reminders = json.optJSONArray("reminders")
                        if (reminders != null) for (index in 0 until reminders.length()) {
                            val item = reminders.optJSONObject(index) ?: continue
                            val dateText = item.optString("date_iso").takeIf { it != "null" }.orEmpty()
                            val timeText = item.optString("time_local").takeIf { it != "null" }.orEmpty()
                            if (item.optBoolean("requires_confirmation") || dateText.isBlank() || timeText.isBlank()) {
                                notes += "زمان یک قرار نیازمند تأیید است؛ خودکار ثبت نشد."
                                continue
                            }
                            val due = runCatching {
                                LocalDateTime.of(LocalDate.parse(dateText), LocalTime.parse(timeText))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            }.getOrNull()
                            if (due == null || due <= System.currentTimeMillis()) {
                                notes += "تاریخ یا ساعت پیشنهادی معتبر نیست."
                                continue
                            }
                            val title = item.optString("title", "جلسه با " + personName).take(160)
                            val lead = item.optInt("remind_days_before", 0).coerceIn(0, 7)
                            val duplicate = db.allReminders().any {
                                it.entryId == sourceEntryId && it.dueAt == due && it.title == title
                            }
                            if (!duplicate) {
                                val id = db.addReminder(sourceEntryId, title, due, lead)
                                db.allReminders().firstOrNull { it.id == id }?.let {
                                    ReminderScheduler.schedule(context, it)
                                }
                                needVoiceNotificationPermission = true
                                notes += "قرار Agent در برنامه کاری ثبت شد: " + title
                            }
                        }
                        val questions = json.optJSONArray("questions")
                        if (questions != null) for (i in 0 until questions.length()) {
                            questions.optString(i).takeIf { it.isNotBlank() }
                                ?.let { notes += "سؤال Agent: " + it.take(240) }
                        }
                        agentMessages = notes.distinct().ifEmpty { listOf("Agent دستور جدیدی تشخیص نداد.") }
                        refresh()
                    }
                }.onFailure { error ->
                    agentMessages = listOf("Agent در دسترس نبود؛ متن و تغییرات محلی حفظ شدند: " +
                        error.localizedMessage.orEmpty().take(160))
                }
            }
        }
    }

    fun ensureRecord(): Long {
        if (recordId == 0L) {
            if (note.isBlank() && personName.isBlank() && companyName.isBlank() && phone.isBlank()) {
                Toast.makeText(context, "ابتدا چیزی در کادر اصلی بنویس یا ضبط کن", Toast.LENGTH_SHORT).show()
                return 0L
            }
            val tags = chosenTags + inferredTags(note)
            recordId = db.save(
                0L, personName, phone, note, tags, companyName,
                selectedGroupId.takeIf { it > 0L }, extraPhones, contactRole, contactCategory
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
        extraPhones = entry.extraPhones
        showExtraPhones = extraPhones.isNotEmpty()
        contactRole = entry.role
        contactCategory = entry.category
        note = entry.note
        lastVoiceActions = emptyList()
        chosenTags = entry.tags
        draftLatitude = entry.latitude
        draftLongitude = entry.longitude
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
        extraPhones = emptyList()
        showExtraPhones = false
        contactRole = ""
        contactCategory = ""
        note = ""
        lastVoiceActions = emptyList()
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
            Toast.makeText(context, "ابتدا اطلاعات پرونده را ثبت کن", Toast.LENGTH_SHORT).show()
        }
    }

    fun callNumber(value: String) {
        if (!openDialer(context, value)) {
            Toast.makeText(context, "برنامه شماره‌گیر پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    fun beginGoogleDictation() {
        if (isRecording || googleVoiceBusy) return
        recordingError = ""
        googleVoiceBusy = true
        googleVoiceStage = "در حال فعال‌کردن سرویس گفتار گوگل..."
        val session = GoogleDictationSession(
            context.applicationContext,
            onStage = { googleVoiceStage = it },
            onFinished = { text, error ->
                googleSession = null
                googleVoiceBusy = false
                googleVoiceStage = ""
                isRecording = false
                if (text.isNullOrBlank()) {
                    recordingError = error ?: "سرویس گفتار گوشی متنی برنگرداند."
                } else {
                    appendRawSpeech(text)
                    Toast.makeText(context, "متن در کادر آماده پردازش است", Toast.LENGTH_SHORT).show()
                }
            }
        )
        googleSession = session
        if (session.start()) {
            isRecording = true
            googleVoiceBusy = false
            recordingStartedAt = SystemClock.elapsedRealtime()
            recordingClock = recordingStartedAt
        } else {
            googleSession = null
            googleVoiceBusy = false
        }
    }

    fun stopGoogleDictation() {
        if (!isRecording || googleVoiceBusy) return
        isRecording = false
        googleVoiceBusy = true
        googleVoiceStage = "پایان گفتار؛ در حال دریافت متن نهایی..."
        googleSession?.stop()
    }

    fun beginRecording() {
        if (isRecording) return
        recordingError = ""
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordingError = "اجازه استفاده از میکروفون داده نشده است."
            return
        }
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val owner = if (recordId > 0L) recordId.toString() else "draft"
        val target = File(folder, "voice_${owner}_${System.currentTimeMillis()}.m4a")
        var candidate: MediaRecorder? = null
        try {
            candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setAudioChannels(1)
            candidate.setAudioSamplingRate(16_000)
            candidate.setAudioEncodingBitRate(48_000)
            candidate.setOutputFile(target.absolutePath)
            candidate.prepare()
            candidate.start()
            recorder = candidate
            audioFile = target
            isRecording = true
            recordingStartedAt = SystemClock.elapsedRealtime()
            recordingClock = recordingStartedAt
        } catch (error: Exception) {
            runCatching { candidate?.reset() }
            runCatching { candidate?.release() }
            recorder = null
            audioFile = null
            isRecording = false
            target.delete()
            recordingError = "ضبط شروع نشد: " + (error.localizedMessage ?: "میکروفون در دسترس نیست")
            Toast.makeText(context, recordingError, Toast.LENGTH_LONG).show()
        }
    }

    fun stopRecording() {
        val r = recorder ?: run {
            isRecording = false
            recordingError = "ضبطی در حال انجام نیست."
            return
        }
        val target = audioFile
        var success = false
        try {
            r.stop()
            success = true
        } catch (error: Exception) {
            recordingError = "ضبط کامل نشد؛ حداقل دو ثانیه صحبت کن و دوباره امتحان کن."
            Toast.makeText(context, recordingError, Toast.LENGTH_LONG).show()
        } finally {
            runCatching { r.release() }
            recorder = null
            audioFile = null
            isRecording = false
        }
        if (!success || target == null || !target.isFile || target.length() < 256L) {
            target?.delete()
            if (recordingError.isBlank()) recordingError = "فایل صوتی خالی است. دوباره ضبط کن."
            return
        }
        try {
            if (recordId > 0L) {
                db.addAttachment(recordId, "audio", target.absolutePath)
                refresh()
            } else {
                addDraftAttachment("audio", target)
            }
            // Original audio stays available for AI analysis of background noise.
            pendingVoiceFilePath = target.absolutePath
            voiceFileReader?.cancel()
            voiceTranscribing = true
            val reader = VoiceFileTranscriber(context.applicationContext)
            voiceFileReader = reader
            reader.transcribe(target, onStage = { googleVoiceStage = it },
                onFinish = { text, error ->
                    voiceTranscribing = false
                    voiceFileReader = null
                    if (!text.isNullOrBlank()) appendRawSpeech(text)
                    else {
                        val placeholder = "صدای ضبط‌شده آماده پردازش است؛ متن اولیه تشخیص داده نشد."
                        if (note.isBlank()) { note = placeholder; save(newNote = placeholder) }
                        agentMessages = listOf(error ?: placeholder)
                    }
                })
        } catch (error: Exception) {
            recordingError = "صدا ذخیره شد، اما اتصال به پرونده کامل نشد: " +
                (error.localizedMessage ?: "خطا")
            Toast.makeText(context, recordingError, Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) {
            if (googleVoiceMode) beginGoogleDictation() else beginRecording()
        }
        else {
            recordingError = "برای ضبط صدا، مجوز میکروفون را در تنظیمات گوشی فعال کن."
            Toast.makeText(context, recordingError, Toast.LENGTH_LONG).show()
        }
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

    fun performGoogleSync() {
        if (googleAccount.isBlank() || googleSyncBusy) return
        googleSyncBusy = true
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    CrmDb(context.applicationContext).use { syncDb ->
                        GoogleContactsBridge(context.applicationContext, syncDb).sync(googleAccount)
                    }
                }
            }
            googleSyncBusy = false
            outcome.onSuccess { result ->
                refresh()
                Toast.makeText(context,
                    "گوگل: ${result.imported} دریافت، ${result.uploaded} ارسال", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(context,
                    "همگام‌سازی انجام نشد: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val googleContactsPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.READ_CONTACTS] == true &&
            grants[Manifest.permission.WRITE_CONTACTS] == true) performGoogleSync()
        else Toast.makeText(context, "بدون دسترسی مخاطبین، همگام‌سازی فعال نمی‌شود",
            Toast.LENGTH_LONG).show()
    }

    fun requestGoogleSync() {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            performGoogleSync()
        else googleContactsPermissions.launch(arrayOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
    }

    val accountPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val chosen = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
            if (chosen.isNotBlank()) {
                googleAccount = chosen
                googlePrefs.edit().putString("account", chosen).apply()
                PoyaraGoogleSyncWorker.schedule(context)
                requestGoogleSync()
            }
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

    val callerPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val contactsAllowed =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        if (notificationsAllowed && contactsAllowed) {
            requestCallerRole()
        } else {
            Toast.makeText(context,
                "برای نمایش تماس مشتریانی که در دفترچه گوشی هم هستند، اجازه مخاطبین و اعلان لازم است.",
                Toast.LENGTH_LONG).show()
        }
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
    LaunchedEffect(needVoiceNotificationPermission) {
        if (needVoiceNotificationPermission) {
            needVoiceNotificationPermission = false
            requestReminderNotificationPermission()
        }
    }

    fun activateCallerContext() {
        val permissions = buildList {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) requestCallerRole()
        else callerPermissions.launch(permissions.toTypedArray())
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

    fun openMap(entryId: Long, allowPick: Boolean = false, previewOnly: Boolean = false) {
        val saved = allEntries.firstOrNull { it.id == entryId }
        val lat = saved?.latitude ?: draftLatitude
        val lon = saved?.longitude ?: draftLongitude
        val locationName = saved?.company?.ifBlank { VoiceLocalFacts.extract(saved.note).company }
            ?.ifBlank { saved.name }?.takeIf { it.isNotBlank() }
            ?: companyName.ifBlank { VoiceLocalFacts.extract(note).company }
                .ifBlank { personName }.ifBlank { "موقعیت مجموعه" }
        val target = Intent(context, OfflineMapActivity::class.java)
            .putExtra("entry_id", entryId)
            .putExtra("allow_pick_location", allowPick)
            .putExtra("preview_only", previewOnly)
            .putExtra("preview_label", locationName)
        if (lat != null && lon != null) {
            target.putExtra("preview_latitude", lat)
            target.putExtra("preview_longitude", lon)
        }
        mapLauncher.launch(target)
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
                val canComplete = !isRecording && pendingAutoTranscriptions.isEmpty() &&
                    (note.isNotBlank() || (personName.isNotBlank() && phone.isNotBlank()))
                FloatingActionButton(
                    modifier = Modifier.size(64.dp),
                    containerColor = if (canComplete) Color(0xFF087866) else Color(0xFFD9E5E3),
                    contentColor = if (canComplete) Color.White else Color(0xFF526C69),
                    shape = RoundedCornerShape(18.dp),
                    onClick = {
                        if (!canComplete) {
                            Toast.makeText(
                                context, if (isRecording || pendingAutoTranscriptions.isNotEmpty())
                                    "ضبط یا تبدیل صوت هنوز کامل نشده است"
                                else "برای ثبت، چیزی در کادر اصلی بنویس یا ویس را تبدیل کن",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (save() > 0L) {
                            Toast.makeText(context, "ثبت شد؛ آماده پرونده بعدی", Toast.LENGTH_SHORT).show()
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
                if (allEntries.isEmpty() && recordId == 0L) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F5))) {
                        Row(Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("انتقال از پویارای قبلی",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                importLauncher.launch(arrayOf(
                                    "application/zip", "application/octet-stream"))
                            }) { Text("بازیابی اطلاعات قبلی") }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = personName,
                        onValueChange = { personName = it; save(newName = it) },
                        label = { Text("نام") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it; save(newPhone = it) },
                        label = { Text("شماره تماس") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1.2f)
                    )
                    TextButton(
                        onClick = {
                            extraPhones = extraPhones + ""
                            showExtraPhones = true
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(27.dp)
                    ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                if (showExtraPhones) {
                    extraPhones.forEachIndexed { index, number ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = number,
                                onValueChange = { value ->
                                    extraPhones = extraPhones.toMutableList().also { it[index] = value }
                                    save()
                                },
                                label = { Text("شماره اضافه ${index + 1}") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                extraPhones = extraPhones.filterIndexed { i, _ -> i != index }
                                save()
                            }) { Text("×") }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        note = it
                        save(newNote = it)
                    },
                    label = { Text("چه چیزی درباره مجموعه می‌دونی؟") },
                    placeholder = {
                        Text("هرچه می‌دانی بنویس یا با ضبط صوت بگو؛ نام‌ها، شماره‌ها، آدرس، جلسه‌ها و پیگیری‌ها...")
                    },
                    minLines = 11,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = contactRole,
                        onValueChange = { contactRole = it; save() },
                        label = { Text("سمت شخص") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = contactCategory,
                        onValueChange = { contactCategory = it; save() },
                        label = { Text("گروه فعالیت") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categoryChoices.forEach { category ->
                        FilterChip(
                            selected = contactCategory == category,
                            onClick = { contactCategory = category; save() },
                            label = { Text(category) }
                        )
                    }
                }
                Text(
                    "این کادر خودکار ذخیره می‌شود؛ متن ویس و اطلاعات قابل تشخیص هم به همین‌جا اضافه می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (lastVoiceActions.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE3F3EF)
                    )) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("آخرین پردازش گفتار", fontWeight = FontWeight.Bold)
                            lastVoiceActions.forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Agent رایگان · Gemini Flash-Lite", fontWeight = FontWeight.SemiBold)
                        Text("مدل رایگان گوگل از طریق سرور خصوصی شما فراخوانی می‌شود. فقط متن همان ویس و نام/مجموعه همین پرونده ارسال می‌شود؛ سایر مخاطبین گوشی فرستاده نمی‌شوند. بدون اتصال، موتور محلی همچنان کار می‌کند.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("توجه: در سهمیه رایگان Gemini، گوگل ممکن است متن ارسالی را برای بهبود محصولاتش استفاده کند؛ اطلاعات مشتری را فقط با رضایت و آگاهی ارسال کن. سهمیه رایگان محدود است؛ خود سرور ممکن است هزینه داشته باشد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = agentPrivacyConsent,
                                onCheckedChange = { accepted ->
                                    agentPrivacyConsent = accepted
                                    if (!accepted) agentOptIn = false
                                    agentPrefs.edit()
                                        .putBoolean("cloud_text_consent_v1", accepted)
                                        .putBoolean("enabled", if (accepted) agentOptIn else false)
                                        .apply()
                                })
                            Text("با ارسال متن گفتار مشتری برای تحلیل ابری موافقم",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(value = agentUrl, onValueChange = { agentUrl = it },
                            label = { Text("آدرس HTTPS سرور Agent") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = agentPairingToken,
                            onValueChange = { agentPairingToken = it },
                            label = { Text("کد اتصال اختصاصی سرور") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    require(agentPrivacyConsent) {
                                        "برای فعال‌سازی Agent، ابتدا اجازه ارسال متن به مدل ابری را تأیید کن."
                                    }
                                    textAgentSettings.save(agentUrl, agentPairingToken)
                                    agentPairingToken = ""
                                    agentOptIn = true
                                    agentPrefs.edit().putBoolean("enabled", true).apply()
                                }.onSuccess {
                                    Toast.makeText(context, "تنظیمات Agent ذخیره شد", Toast.LENGTH_SHORT).show()
                                }.onFailure { error ->
                                    Toast.makeText(context, error.localizedMessage, Toast.LENGTH_LONG).show()
                                }
                            }) { Text("ذخیره اتصال") }
                            Text("فعال", style = MaterialTheme.typography.bodySmall)
                            Switch(checked = agentOptIn,
                                enabled = textAgentSettings.configured && agentPrivacyConsent,
                                onCheckedChange = { value ->
                                    agentOptIn = value && agentPrivacyConsent
                                    agentPrefs.edit().putBoolean("enabled", agentOptIn).apply()
                                })
                        }
                        if (agentBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        agentMessages.forEach { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (isRecording) Color(0xFFFFF0EC) else Color(0xFFE3F3EF)
                )) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (isRecording) "● در حال صحبت؛ متن تا پایان نمایش داده نمی‌شود"
                            else if (googleVoiceBusy) "در حال دریافت نتیجه نهایی..."
                            else "ثبت گفتار مشتری",
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) Color(0xFF9C2929) else Color(0xFF08685F)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = googleVoiceMode,
                                enabled = !isRecording && !googleVoiceBusy,
                                onClick = { googleVoiceMode = true; recordingError = "" },
                                label = { Text("گوگل · متن") }
                            )
                            FilterChip(
                                selected = !googleVoiceMode,
                                enabled = !isRecording && !googleVoiceBusy,
                                onClick = { googleVoiceMode = false; recordingError = "" },
                                label = { Text("ضبط فایل صوتی") }
                            )
                        }
                        Text(
                            if (googleVoiceMode)
                                "گوگل هنگام صحبت گوش می‌دهد؛ هیچ متن زنده‌ای نمایش داده نمی‌شود. بعد از «پایان»، متن خودکار در کادر اصلی ثبت می‌شود. این حالت فایل صوتی ذخیره نمی‌کند."
                            else
                                "فایل صوتی ذخیره می‌شود. تبدیل فایل ذخیره‌شده با سرویس گوگل در بعضی گوشی‌ها پشتیبانی نمی‌شود.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (isRecording) {
                            val seconds = ((recordingClock - recordingStartedAt) / 1000L).coerceAtLeast(0L)
                            Text("%02d:%02d".format(seconds / 60L, seconds % 60L),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold)
                        }
                        if (googleVoiceBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (googleVoiceStage.isNotBlank() && (googleVoiceMode || googleVoiceBusy)) {
                            Text(googleVoiceStage, style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (isRecording) {
                                        if (googleVoiceMode) stopGoogleDictation()
                                        else stopRecording()
                                    } else if (context.checkSelfPermission(
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED) {
                                        if (googleVoiceMode) beginGoogleDictation()
                                        else beginRecording()
                                    } else {
                                        recordingError = "درخواست مجوز میکروفون…"
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !googleVoiceBusy,
                                modifier = Modifier.weight(1f),
                                colors = if (isRecording) ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB42626)
                                ) else ButtonDefaults.buttonColors()
                            ) {
                                Text(
                                    if (isRecording) "■ پایان"
                                    else if (googleVoiceMode) "● شروع گفتار"
                                    else "● ضبط فایل صوتی"
                                )
                            }
                            OutlinedButton(
                                onClick = { takePhoto() },
                                enabled = !isRecording && !googleVoiceBusy
                            ) { Text("📷 عکس") }
                        }
                        if (recordingError.isNotBlank()) {
                            Text(recordingError, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
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
                        val showLatitude = current?.latitude ?: draftLatitude
                        val showLongitude = current?.longitude ?: draftLongitude
                        val locationName = companyName
                            .ifBlank { VoiceLocalFacts.extract(note).company }
                            .ifBlank { current?.company.orEmpty() }
                            .ifBlank { personName }
                            .ifBlank { "موقعیت مجموعه" }
                        if (showLatitude != null && showLongitude != null) {
                            Text(
                                "نام مکان: " + locationName,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF08685F)
                            )
                            ContactMapPreview(
                                latitude = showLatitude,
                                longitude = showLongitude,
                                title = locationName
                            )
                            Text(
                                if (current?.latitude != null) "پیش‌نمایش موقعیت ذخیره‌شده"
                                else "پیش‌نمایش موقعیت انتخاب‌شده؛ با ثبت پرونده ذخیره می‌شود",
                                style = MaterialTheme.typography.labelSmall
                            )
                            OutlinedButton(
                                onClick = { openMap(recordId, previewOnly = true) }
                            ) { Text("📍 مشاهده موقعیت روی نقشه بزرگ") }
                        }
                        Button(
                            onClick = { openMap(recordId, allowPick = true) }
                        ) {
                            Text(if (showLatitude != null) "⌖ تغییر موقعیت ثبت‌شده" else "⌖ ثبت روی نقشه یا GPS")
                        }
                    }
                }

                VoiceAgentPanel(
                    autoTranscribePaths = pendingAutoTranscriptions,
                    onAutoConsumed = { consumed ->
                        pendingAutoTranscriptions = pendingAutoTranscriptions.filterNot { it == consumed }
                    },
                    audioPaths = files.filter { it.type == "audio" }.map { it.filePath } +
                        draftAttachments.mapNotNull { encoded ->
                            val parts = encoded.split("|", limit = 2)
                            if (parts.size == 2 && parts[0] == "audio") parts[1] else null
                        },
                    onTranscribed = { transcript -> addSpeechToCapture(transcript) }
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

                if (additionalNotes.isNotEmpty()) {
                    Text("یادداشت‌های قدیمی پرونده", fontWeight = FontWeight.SemiBold)
                    additionalNotes.forEach { item ->
                        Text(item.text, modifier = Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

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
                            if (callerEnabled) "فعال است؛ فقط تماس شماره‌های ثبت‌شده در CRM اعلان شناسایی دریافت می‌کنند و با لمس آن کارت تماس پویارا باز می‌شود."
                            else "برای نمایش نام، عکس، سمت و مجموعه مشتریان ثبت‌شده روی تماس عادی گوشی، شناسایی تماس کاری را فعال کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!callerEnabled) {
                            TextButton(onClick = { activateCallerContext() }) {
                                Text("فعال‌سازی شناسایی تماس")
                            }
                        }
                        HorizontalDivider()
                        Text("شماره‌گیر اصلی گوشی تغییر نمی‌کند؛ تماس‌های خارج از پویارا هیچ اعلانی از CRM دریافت نمی‌کنند.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("کارت تماس کاری جایگزین دکمه پاسخ و رد تماس اندروید نیست؛ این کنترل‌ها در صفحه تماس معمولی گوشی باقی می‌مانند.",
                            style = MaterialTheme.typography.bodySmall)
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
            // Backup data support is retained for migration, but controls are hidden from contacts.
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("مخاطبین مشترک با Google Contacts", fontWeight = FontWeight.SemiBold)
                    Text(if (googleAccount.isBlank()) "حساب گوگل انتخاب نشده است"
                        else "حساب انتخاب‌شده: " + googleAccount,
                        style = MaterialTheme.typography.bodySmall)
                    Text("فقط مخاطبین دارای شناسه پویارا وارد می‌شوند. با همگام‌سازی، نام، شماره، سمت، گروه و یادداشت‌های CRM در Google Contacts همین حساب ذخیره می‌شوند. همگام‌سازی خود Google باید در تنظیمات گوشی روشن باشد.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching {
                                accountPicker.launch(AccountManager.newChooseAccountIntent(
                                    null, null, arrayOf("com.google"), false,
                                    null, null, null, null))
                            }.onFailure {
                                Toast.makeText(context, "حساب گوگل را ابتدا به تنظیمات گوشی اضافه کن",
                                    Toast.LENGTH_LONG).show()
                            }
                        }) { Text("انتخاب حساب گوگل") }
                        if (googleAccount.isNotBlank()) {
                            OutlinedButton(onClick = { requestGoogleSync() },
                                enabled = !googleSyncBusy) {
                                Text(if (googleSyncBusy) "در حال همگام‌سازی" else "همگام‌سازی")
                            }
                        }
                    }
                }
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                (listOf("همه گروه‌ها") + allEntries.map { it.category.ifBlank { "دسته‌بندی‌نشده" } }.distinct().sorted()).forEach { category ->
                    FilterChip(selected = filterCategory == category,
                        onClick = { filterCategory = category }, label = { Text(category) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                (listOf("همه سمت‌ها") + allEntries.mapNotNull { it.role.takeIf(String::isNotBlank) }.distinct().sorted()).forEach { role ->
                    FilterChip(selected = filterRole == role,
                        onClick = { filterRole = role }, label = { Text(role) })
                }
            }
            val filtered = allEntries.filter { entry ->
                (filterTag == "همه" || filterTag in entry.tags) &&
                    (filterRole == "همه سمت‌ها" || filterRole == entry.role) &&
                    (filterCategory == "همه گروه‌ها" || filterCategory == entry.category.ifBlank { "دسته‌بندی‌نشده" }) &&
                    (search.isBlank() || listOf(entry.name, entry.phone, entry.note, entry.company, entry.tags.joinToString())
                        .any { it.contains(search, ignoreCase = true) } ||
                        db.notes(entry.id).any { it.text.contains(search, ignoreCase = true) })
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filtered.sortedWith(compareBy<ContactEntry> { it.category }.thenBy { it.company })
                    .groupBy { it.category.ifBlank { "دسته‌بندی‌نشده" } }
                    .forEach { (category, categoryMembers) ->
                    item(key = "category_${category}") {
                        Text("${category} · ${categoryMembers.size} مخاطب",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                    categoryMembers.groupBy { it.groupId ?: it.id }.forEach { (groupRootId, members) ->
                    val groupRoot = allEntries.firstOrNull { it.id == groupRootId } ?: members.first()
                    val groupTitle = groupRoot.company.ifBlank { groupRoot.name }
                        .ifBlank { groupRoot.note.lineSequence().firstOrNull().orEmpty().take(48) }
                        .ifBlank { "پرونده بدون عنوان" }
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
                                ) else if (entry.company.isBlank() && entry.note.isNotBlank()) {
                                    Text(
                                        entry.note.lineSequence().firstOrNull().orEmpty().take(64),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                if (entry.company.isNotBlank()) Text(
                                    entry.company,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (entry.phone.isNotBlank()) Text(entry.phone, fontWeight = FontWeight.SemiBold)
                                if (entry.extraPhones.isNotEmpty()) Text(entry.extraPhones.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall)
                                if (entry.role.isNotBlank()) Text("سمت: ${entry.role}",
                                    style = MaterialTheme.typography.labelMedium)
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
                                    if (entry.latitude != null && entry.longitude != null) {
                                        TextButton(onClick = {
                                            openMap(entry.id, previewOnly = true)
                                        }) { Text("📍 نقشه") }
                                    }
                                    TextButton(onClick = { open(entry) }) { Text("پرونده") }
                                }
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
                        Modifier.fillMaxWidth().heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("نتیجه کارت بعد از تأیید به همان کادر بزرگ اطلاعات اضافه می‌شود؛ نیازی به پرکردن فیلد جدا نیست.")
                        val lines = listOfNotNull(
                            scanPersonName.trim().takeIf { it.isNotBlank() }?.let { "نام: " + it },
                            scanCompany.trim().takeIf { it.isNotBlank() }?.let { "مجموعه: " + it },
                            scanPhone.trim().takeIf { it.isNotBlank() }?.let { "شماره: " + it },
                            scanAddress.trim().takeIf { it.isNotBlank() }?.let { "آدرس: " + it },
                            scanEmail.trim().takeIf { it.isNotBlank() }?.let { "ایمیل: " + it },
                            scanWebsite.trim().takeIf { it.isNotBlank() }?.let { "وب‌سایت: " + it },
                            scanRawText.trim().takeIf { it.isNotBlank() }?.let { "متن کارت: " + it }
                        )
                        Text(lines.joinToString("\n").ifBlank { "متنی از کارت خوانده نشد" })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val section = buildList {
                            if (scanPersonName.isNotBlank()) add("نام: " + scanPersonName.trim())
                            if (scanCompany.isNotBlank()) add("مجموعه: " + scanCompany.trim())
                            if (scanPhone.isNotBlank()) add("شماره: " + scanPhone.trim())
                            if (scanAddress.isNotBlank()) add("آدرس: " + scanAddress.trim())
                            if (scanEmail.isNotBlank()) add("ایمیل: " + scanEmail.trim())
                            if (scanWebsite.isNotBlank()) add("وب‌سایت: " + scanWebsite.trim())
                            if (scanRawText.isNotBlank()) add("متن کارت: " + scanRawText.trim())
                        }.joinToString("\n")
                        if (section.isNotBlank() && !note.contains(section)) {
                            note = listOf(note.trim(), "اطلاعات کارت:\n" + section)
                                .filter { it.isNotBlank() }.joinToString("\n\n")
                        }
                        // Preserve previous hidden metadata and fill only missing
                        // values; the user sees one combined capture textbox.
                        personName = personName.ifBlank { scanPersonName.trim() }
                        companyName = companyName.ifBlank { scanCompany.trim() }
                        phone = phone.ifBlank { scanPhone.trim() }
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
                    }) { Text("افزودن به کادر اصلی") }
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
