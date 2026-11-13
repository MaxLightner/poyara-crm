package com.poyara.crm

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opt-in bridge using the Android Google-contacts account already on the device.
 * No Gmail password, Google API key or Google OAuth client secret is stored here.
 * Only contacts with a POYARA marker are imported; unrelated personal contacts are ignored.
 *
 * Google account sync happens in Android's Contacts sync adapter. The app cannot
 * guarantee that Android/Google sync is enabled or when another phone receives it.
 */
internal class GoogleContactsBridge(private val context: Context, private val db: CrmDb) {
    companion object {
        private const val ACCOUNT_TYPE = "com.google"
        private const val MARKER = "POYARA_CRM_V1:"
        const val PREFS = "poyara_google_contacts"
    }
    internal data class Result(val imported: Int, val uploaded: Int, val skipped: Int)
    private data class Remote(val rawId: Long, val payload: JSONObject)

    fun sync(accountName: String): Result {
        check(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED) {
            "برای همگام‌سازی باید اجازه خواندن و نوشتن مخاطبین را بدهی"
        }
        require(accountName.isNotBlank()) { "حساب گوگل انتخاب نشده است" }
        val current = googleRawContacts(accountName)
        val bySyncId = current.values.mapNotNull { remote ->
            remote.payload.optString("sync_id").takeIf { it.isNotBlank() }?.let { it to remote }
        }.toMap()
        var imported = 0
        var uploaded = 0
        var skipped = 0
        // First merge the server-side copies into SQLite; then publish local newer changes.
        for ((syncId, remote) in bySyncId) {
            val person = remote.payload
            val name = person.optString("name").take(150)
            val phone = person.optString("phone").take(40)
            if (name.isBlank() && phone.isBlank()) { skipped++; continue }
            val local = db.all().firstOrNull { it.syncId == syncId }
            if (local == null || person.optLong("updated_at") > local.updatedAt) {
                val extras = person.optJSONArray("extra_phones").asStrings().take(10)
                val tags = person.optJSONArray("tags").asStrings().toSet()
                val samePhone = db.findByPhone(phone)?.takeIf {
                    it.syncId != syncId && it.syncId !in bySyncId.keys
                }
                val id = local?.id ?: samePhone?.id ?: 0L
                db.save(id, name, phone, person.optString("note").take(10000),
                    tags, person.optString("company").take(150), local?.groupId,
                    extras, person.optString("role").take(100),
                    person.optString("category").take(100), syncId)
                if (samePhone != null) db.adoptSyncId(samePhone.id, syncId)
                imported++
            }
        }
        for (entry in db.all()) {
            if (entry.name.isBlank() && entry.phone.isBlank()) { skipped++; continue }
            val remote = bySyncId[entry.syncId]
            if (remote == null || entry.updatedAt > remote.payload.optLong("updated_at")) {
                upsert(accountName, entry, remote?.rawId)
                uploaded++
            }
        }
        return Result(imported, uploaded, skipped)
    }

    private fun googleRawContacts(accountName: String): Map<Long, Remote> {
        val result = mutableMapOf<Long, Remote>()
        // Query tagged notes first. Never perform one query per personal Google contact:
        // a personal account can have thousands of unrelated records.
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Note.NOTE} LIKE ?",
            arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, MARKER + "%"),
            null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawId = cursor.getLong(0)
                val note = cursor.getString(1).orEmpty()
                if (!note.startsWith(MARKER)) continue
                val belongsToChosenAccount = context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts._ID),
                    "${ContactsContract.RawContacts._ID}=? AND " +
                        "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                        "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                        "${ContactsContract.RawContacts.DELETED}=0",
                    arrayOf(rawId.toString(), ACCOUNT_TYPE, accountName), null
                )?.use { it.moveToFirst() } == true
                if (!belongsToChosenAccount) continue
                runCatching { JSONObject(note.removePrefix(MARKER)) }.getOrNull()
                    ?.let { payload -> result[rawId] = Remote(rawId, payload) }
            }
        }
        return result
    }

    private fun upsert(accountName: String, entry: ContactEntry, rawId: Long?) {
        val resolver = context.contentResolver
        val ops = arrayListOf<ContentProviderOperation>()
        val raw = ContactsContract.RawContacts.CONTENT_URI
        val data = ContactsContract.Data.CONTENT_URI
        if (rawId == null) {
            ops += ContentProviderOperation.newInsert(raw)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .build()
        } else {
            // Our own raw contact only: never mutate aggregated personal Google contacts.
            for (mime in listOf(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
            )) {
                ops += ContentProviderOperation.newDelete(data)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(rawId.toString(), mime)
                    ).build()
            }
        }
        fun builder(mime: String): ContentProviderOperation.Builder {
            val op = ContentProviderOperation.newInsert(data)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
            return if (rawId == null) op.withValueBackReference(
                ContactsContract.Data.RAW_CONTACT_ID, 0)
            else op.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        }
        ops += builder(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                entry.name.ifBlank { entry.company }).build()
        (listOf(entry.phone) + entry.extraPhones).filter { it.isNotBlank() }.distinct().forEachIndexed { index, number ->
            ops += builder(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, normalizePhone(number))
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                    if (index == 0) ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER).build()
        }
        if (entry.company.isNotBlank() || entry.role.isNotBlank()) {
            ops += builder(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, entry.company)
                .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, entry.role).build()
        }
        val json = JSONObject()
            .put("sync_id", entry.syncId).put("updated_at", entry.updatedAt)
            .put("name", entry.name).put("phone", entry.phone).put("company", entry.company)
            .put("role", entry.role).put("category", entry.category)
            .put("note", entry.note.take(10000))
            .put("extra_phones", JSONArray(entry.extraPhones))
            .put("tags", JSONArray(entry.tags.toList()))
        ops += builder(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, MARKER + json.toString()).build()
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }
}

private fun JSONArray?.asStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
