package com.poyara.crm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ContactEntry(
    val id: Long,
    val name: String,
    val phone: String,
    val note: String,
    val company: String,
    val tags: Set<String>,
    val updatedAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val groupId: Long? = null
)

data class ContactNote(
    val id: Long,
    val entryId: Long,
    val text: String,
    val createdAt: Long
)

data class AttachmentEntry(
    val id: Long,
    val recordId: Long,
    val type: String,
    val filePath: String,
    val createdAt: Long
)

data class WorkReminder(
    val id: Long,
    val entryId: Long,
    val title: String,
    val dueAt: Long,
    val remindDaysBefore: Int,
    val done: Boolean
)

data class VoiceReview(
    val jobId: String,
    val audioPath: String,
    val resultJson: String,
    val applied: Boolean
)

data class VoiceUploadJob(
    val jobId: String,
    val audioPath: String,
    val entryId: Long,
    val ownerEmail: String,
    val status: String,
    val attempts: Int,
    val lastError: String
)

/**
 * Local-first storage: no Contacts Provider access and no network dependency.
 * A text edit writes immediately; image/voice files remain in private app storage.
 */
class CrmDb(context: Context) : SQLiteOpenHelper(context, "poyara_crm.db", null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               name TEXT NOT NULL DEFAULT '',
               phone TEXT NOT NULL DEFAULT '',
               note TEXT NOT NULL DEFAULT '',
               company TEXT NOT NULL DEFAULT '',
               tags TEXT NOT NULL DEFAULT '',
               created_at INTEGER NOT NULL,
               updated_at INTEGER NOT NULL,
               latitude REAL,
               longitude REAL,
               location_source TEXT,
               group_id INTEGER
            )"""
        )
        db.execSQL(
            """CREATE TABLE attachments (
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               record_id INTEGER NOT NULL,
               kind TEXT NOT NULL,
               file_path TEXT NOT NULL,
               created_at INTEGER NOT NULL,
               FOREIGN KEY(record_id) REFERENCES entries(id)
            )"""
        )
        db.execSQL("CREATE INDEX attachments_record ON attachments(record_id)")
        createNotesTable(db)
        createCommunicationsTables(db)
        createRemindersTable(db)
        createVoiceReviewsTable(db)
        createVoiceUploadTable(db)
    }

    private fun createNotesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notes (
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               entry_id INTEGER NOT NULL,
               text TEXT NOT NULL DEFAULT '',
               created_at INTEGER NOT NULL,
               FOREIGN KEY(entry_id) REFERENCES entries(id)
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS notes_entry ON notes(entry_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createNotesTable(db)
        if (oldVersion < 3) createCommunicationsTables(db)
        if (oldVersion < 4) addLocationColumns(db)
        if (oldVersion < 5) addNameColumn(db)
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE entries ADD COLUMN group_id INTEGER")
            createRemindersTable(db)
        }
        if (oldVersion < 7) createVoiceReviewsTable(db)
        if (oldVersion < 8) createVoiceUploadTable(db)
    }

    private fun createVoiceUploadTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS voice_uploads (
            job_id TEXT PRIMARY KEY,
            audio_path TEXT NOT NULL UNIQUE,
            entry_id INTEGER NOT NULL DEFAULT 0,
            owner_email TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS voice_uploads_status ON voice_uploads(status)")
    }

    fun voiceUpload(audioPath: String): VoiceUploadJob? =
        readableDatabase.query("voice_uploads", null, "audio_path=?",
            arrayOf(audioPath), null, null, null).use { cur ->
            if (!cur.moveToFirst()) null else VoiceUploadJob(
                cur.getString(cur.getColumnIndexOrThrow("job_id")),
                cur.getString(cur.getColumnIndexOrThrow("audio_path")),
                cur.getLong(cur.getColumnIndexOrThrow("entry_id")),
                cur.getString(cur.getColumnIndexOrThrow("owner_email")),
                cur.getString(cur.getColumnIndexOrThrow("status")),
                cur.getInt(cur.getColumnIndexOrThrow("attempts")),
                cur.getString(cur.getColumnIndexOrThrow("last_error"))
            )
        }

    fun queueVoiceUpload(
        audioPath: String, entryId: Long = 0L, ownerEmail: String = ""
    ): VoiceUploadJob {
        val previous = voiceUpload(audioPath)
        if (previous != null) return previous
        require(java.io.File(audioPath).isFile) { "فایل ویس پیدا نشد" }
        val id = java.util.UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("voice_uploads", null, ContentValues().apply {
            put("job_id", id)
            put("audio_path", audioPath)
            put("entry_id", entryId)
            put("owner_email", ownerEmail.trim().lowercase(java.util.Locale.ROOT))
            put("status", "pending")
            put("attempts", 0)
            put("last_error", "")
            put("updated_at", System.currentTimeMillis())
        })
        return voiceUpload(audioPath)!!
    }

    fun pendingVoiceUploads(): List<VoiceUploadJob> {
        val out = mutableListOf<VoiceUploadJob>()
        readableDatabase.query(
            "voice_uploads", arrayOf("audio_path"),
            "status IN ('pending','uploading','retry')", null, null, null, "updated_at ASC"
        ).use { cur ->
            while (cur.moveToNext()) voiceUpload(cur.getString(0))?.let { out += it }
        }
        return out
    }

    fun markVoiceUpload(audioPath: String, status: String, error: String = "") {
        val values = ContentValues().apply {
            put("status", status)
            put("last_error", error.take(350))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("voice_uploads", values, "audio_path=?", arrayOf(audioPath))
    }

    fun retryVoiceUpload(audioPath: String) {
        writableDatabase.update("voice_uploads", ContentValues().apply {
            put("status", "pending")
            put("last_error", "")
            put("updated_at", System.currentTimeMillis())
        }, "audio_path=? AND status='failed'", arrayOf(audioPath))
    }

    fun incrementVoiceAttempts(audioPath: String) {
        writableDatabase.execSQL(
            "UPDATE voice_uploads SET attempts=attempts+1, updated_at=? WHERE audio_path=?",
            arrayOf(System.currentTimeMillis(), audioPath)
        )
    }

    private fun createVoiceReviewsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS voice_reviews (
            job_id TEXT PRIMARY KEY,
            audio_path TEXT NOT NULL UNIQUE,
            result_json TEXT NOT NULL,
            applied INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS voice_reviews_audio ON voice_reviews(audio_path)")
    }

    fun voiceReview(audioPath: String): VoiceReview? =
        readableDatabase.query(
            "voice_reviews", null, "audio_path=?", arrayOf(audioPath),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else VoiceReview(
                cursor.getString(cursor.getColumnIndexOrThrow("job_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("result_json")),
                cursor.getInt(cursor.getColumnIndexOrThrow("applied")) == 1
            )
        }

    fun saveVoiceReview(jobId: String, audioPath: String, json: String) {
        require(audioPath.isNotBlank() && json.isNotBlank())
        val previous = voiceReview(audioPath)
        require(previous?.applied != true) { "این ویس قبلاً وارد پرونده‌ها شده است" }
        writableDatabase.insertWithOnConflict(
            "voice_reviews", null, ContentValues().apply {
                put("job_id", jobId)
                put("audio_path", audioPath)
                put("result_json", json)
                put("applied", 0)
                put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun markVoiceReviewApplied(jobId: String) {
        val count = writableDatabase.update(
            "voice_reviews", ContentValues().apply { put("applied", 1) },
            "job_id=? AND applied=0", arrayOf(jobId)
        )
        require(count == 1) { "نتیجه صوتی قبلاً وارد شده یا پیدا نشد" }
    }


    private fun createRemindersTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS reminders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_id INTEGER NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            due_at INTEGER NOT NULL,
            remind_days_before INTEGER NOT NULL DEFAULT 1,
            done INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS reminders_due ON reminders(due_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS reminders_entry ON reminders(entry_id)")
    }

    fun addReminder(entryId: Long, title: String, dueAt: Long, remindDaysBefore: Int): Long {
        require(entryId > 0 && dueAt > System.currentTimeMillis())
        val values = ContentValues().apply {
            put("entry_id", entryId)
            put("title", title.trim())
            put("due_at", dueAt)
            put("remind_days_before", remindDaysBefore.coerceIn(0, 7))
            put("done", 0)
        }
        return writableDatabase.insertOrThrow("reminders", null, values)
    }

    fun remindersBetween(startAt: Long, endAt: Long): List<WorkReminder> {
        val result = mutableListOf<WorkReminder>()
        readableDatabase.query(
            "reminders", null, "due_at>=? AND due_at<?",
            arrayOf(startAt.toString(), endAt.toString()), null, null, "due_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += reminderFrom(cursor)
        }
        return result
    }

    fun allReminders(includeDone: Boolean = false): List<WorkReminder> {
        val result = mutableListOf<WorkReminder>()
        readableDatabase.query(
            "reminders", null, if (includeDone) null else "done=0",
            null, null, null, "due_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += reminderFrom(cursor)
        }
        return result
    }

    fun deleteReminder(id: Long) {
        writableDatabase.delete("reminders", "id=?", arrayOf(id.toString()))
    }

    fun completeReminder(id: Long, done: Boolean = true) {
        writableDatabase.update(
            "reminders", ContentValues().apply { put("done", if (done) 1 else 0) },
            "id=?", arrayOf(id.toString())
        )
    }

    private fun reminderFrom(cursor: android.database.Cursor) = WorkReminder(
        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        cursor.getLong(cursor.getColumnIndexOrThrow("entry_id")),
        cursor.getString(cursor.getColumnIndexOrThrow("title")),
        cursor.getLong(cursor.getColumnIndexOrThrow("due_at")),
        cursor.getInt(cursor.getColumnIndexOrThrow("remind_days_before")),
        cursor.getInt(cursor.getColumnIndexOrThrow("done")) == 1
    )

    fun saveNote(noteId: Long, entryId: Long, text: String): Long {
        require(entryId > 0)
        val values = ContentValues().apply { put("text", text) }
        return if (noteId > 0) {
            writableDatabase.update("notes", values, "id=? AND entry_id=?",
                arrayOf(noteId.toString(), entryId.toString()))
            noteId
        } else {
            values.put("entry_id", entryId)
            values.put("created_at", System.currentTimeMillis())
            writableDatabase.insertOrThrow("notes", null, values)
        }
    }

    fun notes(entryId: Long): List<ContactNote> {
        val result = mutableListOf<ContactNote>()
        readableDatabase.query("notes", null, "entry_id=?", arrayOf(entryId.toString()),
            null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) {
                result += ContactNote(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    entryId,
                    cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return result
    }

    private fun createCommunicationsTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS call_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_id INTEGER NOT NULL,
            direction TEXT NOT NULL,
            phone TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sms_drafts (
            entry_id INTEGER PRIMARY KEY,
            body TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sms_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_id INTEGER NOT NULL,
            body TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
    }

    fun findByPhone(number: String): ContactEntry? {
        val target = normalizePhone(number)
        if (target.isBlank()) return null
        val matches = all().filter { normalizePhone(it.phone) == target }
        return matches.singleOrNull()
    }

    fun addCallEvent(entryId: Long, direction: String, phone: String) {
        writableDatabase.insert("call_events", null, ContentValues().apply {
            put("entry_id", entryId)
            put("direction", direction)
            put("phone", phone)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun callEvents(entryId: Long): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.query(
            "call_events", arrayOf("direction", "created_at"),
            "entry_id=?", arrayOf(entryId.toString()),
            null, null, "created_at DESC", "15"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val direction = cursor.getString(0)
                val date = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                ).format(java.util.Date(cursor.getLong(1)))
                result += "$direction · $date"
            }
        }
        return result
    }

    fun smsDraft(entryId: Long): String =
        readableDatabase.query(
            "sms_drafts", arrayOf("body"), "entry_id=?",
            arrayOf(entryId.toString()), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }

    fun saveSmsDraft(entryId: Long, body: String) {
        writableDatabase.insertWithOnConflict(
            "sms_drafts", null, ContentValues().apply {
                put("entry_id", entryId)
                put("body", body)
                put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun markSmsOpened(entryId: Long, body: String) {
        writableDatabase.insert("sms_events", null, ContentValues().apply {
            put("entry_id", entryId)
            put("body", body)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun smsHandoffs(entryId: Long): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.query(
            "sms_events", arrayOf("body", "created_at"),
            "entry_id=?", arrayOf(entryId.toString()),
            null, null, "created_at DESC", "10"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val date = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                ).format(java.util.Date(cursor.getLong(1)))
                result += "$date · ${cursor.getString(0)}"
            }
        }
        return result
    }

    private fun addNameColumn(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN name TEXT NOT NULL DEFAULT ''")
    }

    private fun addLocationColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN latitude REAL")
        db.execSQL("ALTER TABLE entries ADD COLUMN longitude REAL")
        db.execSQL("ALTER TABLE entries ADD COLUMN location_source TEXT")
    }

    /**
     * Save a point for this person. Known people in the same company without any
     * coordinates inherit the point; independently located branches are preserved.
     */
    fun setLocation(id: Long, latitude: Double, longitude: Double, source: String) {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val company = db.rawQuery(
                "SELECT company FROM entries WHERE id=?", arrayOf(id.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
            val values = ContentValues().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("location_source", source)
            }
            db.update("entries", values, "id=?", arrayOf(id.toString()))
            if (company.isNotBlank()) {
                db.update(
                    "entries", values,
                    "company=? AND latitude IS NULL AND longitude IS NULL",
                    arrayOf(company)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun save(
        id: Long,
        name: String,
        phone: String,
        note: String,
        tags: Set<String>,
        company: String = "",
        groupId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        val resolvedCompany = company.trim().ifBlank { extractCompany(note) }
        val values = ContentValues().apply {
            put("name", name.trim())
            put("phone", phone.trim())
            put("note", note)
            put("company", resolvedCompany)
            if (groupId != null && groupId > 0L) put("group_id", groupId) else putNull("group_id")
            put("tags", tags.sorted().joinToString("|"))
            put("updated_at", now)
        }
        return if (id > 0) {
            writableDatabase.update("entries", values, "id=?", arrayOf(id.toString()))
            id
        } else {
            values.put("created_at", now)
            writableDatabase.insertOrThrow("entries", null, values)
        }
    }

    fun updateGroupCompany(rootEntryId: Long, company: String) {
        require(rootEntryId > 0L)
        writableDatabase.update(
            "entries",
            ContentValues().apply { put("company", company.trim()) },
            "id=? OR group_id=?",
            arrayOf(rootEntryId.toString(), rootEntryId.toString())
        )
    }

    fun setGroup(entryId: Long, rootEntryId: Long?) {
        require(entryId > 0L && (rootEntryId == null || rootEntryId > 0L))
        if (rootEntryId == entryId) return
        writableDatabase.update(
            "entries",
            ContentValues().apply {
                if (rootEntryId == null) putNull("group_id") else put("group_id", rootEntryId)
            },
            "id=?",
            arrayOf(entryId.toString())
        )
    }

    fun addAttachment(recordId: Long, kind: String, path: String): Long {
        require(recordId > 0)
        val values = ContentValues().apply {
            put("record_id", recordId)
            put("kind", kind)
            put("file_path", path)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("attachments", null, values)
    }

    fun attachments(recordId: Long): List<AttachmentEntry> {
        val result = mutableListOf<AttachmentEntry>()
        readableDatabase.query(
            "attachments", null, "record_id=?", arrayOf(recordId.toString()),
            null, null, "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AttachmentEntry(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    recordId,
                    cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                    cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return result
    }

    fun all(): List<ContactEntry> {
        val result = mutableListOf<ContactEntry>()
        readableDatabase.query("entries", null, null, null, null, null, "updated_at DESC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val encoded = cursor.getString(cursor.getColumnIndexOrThrow("tags"))
                    result += ContactEntry(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("phone")),
                        cursor.getString(cursor.getColumnIndexOrThrow("note")),
                        cursor.getString(cursor.getColumnIndexOrThrow("company")),
                        encoded.split("|").filter { it.isNotBlank() }.toSet(),
                        cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                        cursor.getColumnIndexOrThrow("latitude").let { idx ->
                            if (cursor.isNull(idx)) null else cursor.getDouble(idx)
                        },
                        cursor.getColumnIndexOrThrow("longitude").let { idx ->
                            if (cursor.isNull(idx)) null else cursor.getDouble(idx)
                        },
                        cursor.getColumnIndexOrThrow("location_source").let { idx ->
                            if (cursor.isNull(idx)) null else cursor.getString(idx)
                        },
                        cursor.getColumnIndexOrThrow("group_id").let { idx ->
                            if (cursor.isNull(idx)) null else cursor.getLong(idx)
                        }
                    )
                }
            }
        return result
    }

    // Small deterministic heuristic. Never invents a person's name; exact company
    // strings found in separate notes form one group in the list.
    private fun extractCompany(text: String): String {
        val regex = Regex("""(?:فروشگاه\s+)?(?:مبلمان|کابینت|شرکت|دفتر معماری)\s+[\p{L}\p{N}_‌-]+""")
        return regex.find(text)?.value?.removePrefix("فروشگاه ")?.trim().orEmpty()
    }
}
