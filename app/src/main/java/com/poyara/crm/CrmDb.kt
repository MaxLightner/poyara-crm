package com.poyara.crm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ContactEntry(
    val id: Long,
    val phone: String,
    val note: String,
    val company: String,
    val tags: Set<String>,
    val updatedAt: Long
)

data class AttachmentEntry(
    val id: Long,
    val recordId: Long,
    val type: String,
    val filePath: String,
    val createdAt: Long
)

/**
 * Local-first storage: no Contacts Provider access and no network dependency.
 * A text edit writes immediately; image/voice files remain in private app storage.
 */
class CrmDb(context: Context) : SQLiteOpenHelper(context, "poyara_crm.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               phone TEXT NOT NULL DEFAULT '',
               note TEXT NOT NULL DEFAULT '',
               company TEXT NOT NULL DEFAULT '',
               tags TEXT NOT NULL DEFAULT '',
               created_at INTEGER NOT NULL,
               updated_at INTEGER NOT NULL
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1. Future migrations must preserve user notes and recordings.
    }

    fun save(id: Long, phone: String, note: String, tags: Set<String>): Long {
        val now = System.currentTimeMillis()
        val company = extractCompany(note)
        val values = ContentValues().apply {
            put("phone", phone.trim())
            put("note", note)
            put("company", company)
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
                        cursor.getString(cursor.getColumnIndexOrThrow("phone")),
                        cursor.getString(cursor.getColumnIndexOrThrow("note")),
                        cursor.getString(cursor.getColumnIndexOrThrow("company")),
                        encoded.split("|").filter { it.isNotBlank() }.toSet(),
                        cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    )
                }
            }
        return result
    }

    // Small deterministic heuristic. Never invents a person's name; exact company
    // strings found in separate notes form one group in the list.
    private fun extractCompany(text: String): String {
        val regex = Regex("""(مبلمان|کابینت|فروشگاه|شرکت|دفتر معماری)\s+([\p{L}\p{N}_‌-]+)""")
        return regex.find(text)?.value?.trim().orEmpty()
    }
}
