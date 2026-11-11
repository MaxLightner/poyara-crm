package com.poyara.crm

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable backup containing metadata + original audio/photo files.
 * Import is additive: existing entries are never overwritten.
 */
class CrmBackup(private val context: Context, private val db: CrmDb) {
    fun exportTo(uri: Uri): Int {
        val entries = db.all()
        val manifest = JSONObject().put("format", "poyara-crm-v1")
        val contacts = JSONArray()
        val byId = entries.associateBy { it.id }
        val media = mutableListOf<Pair<String, File>>()
        entries.forEach { entry ->
            val root = entry.groupId?.let { byId[it] }
            val obj = JSONObject()
                .put("name", entry.name)
                .put("phone", entry.phone)
                .put("company", entry.company)
                .put("groupRootPhone", root?.phone.orEmpty())
                .put("note", entry.note)
                .put("tags", JSONArray(entry.tags.toList()))
            if (entry.latitude != null && entry.longitude != null) {
                obj.put("latitude", entry.latitude)
                obj.put("longitude", entry.longitude)
                obj.put("locationSource", entry.locationSource ?: "backup")
            }
            val notes = JSONArray()
            db.notes(entry.id).forEach { item ->
                notes.put(JSONObject().put("text", item.text))
            }
            obj.put("notes", notes)
            val files = JSONArray()
            db.attachments(entry.id).forEach { item ->
                val source = File(item.filePath)
                if (source.isFile) {
                    val ext = if (item.type == "audio") "m4a" else "jpg"
                    val archivePath = "media/${entry.id}/${item.id}.$ext"
                    files.put(JSONObject().put("kind", item.type).put("file", archivePath))
                    media.add(archivePath to source)
                }
            }
            obj.put("attachments", files)
            contacts.put(obj)
        }
        manifest.put("contacts", contacts)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                media.forEach { (archivePath, source) ->
                    zip.putNextEntry(ZipEntry(archivePath))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot write backup")
        return entries.size
    }

    fun importFrom(uri: Uri): Int {
        val staging = File(context.cacheDir, "crm_import_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            var totalBytes = 0L
            val MAX_BYTES = 100L * 1024 * 1024
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        require(!entry.isDirectory && (name == "manifest.json" || name.startsWith("media/"))) {
                            "Invalid backup entry"
                        }
                        val destination = File(staging, name)
                        require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Invalid backup path"
                        }
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val length = zip.read(buffer)
                                if (length < 0) break
                                totalBytes += length
                                require(totalBytes <= MAX_BYTES) { "Backup exceeds 100 MB" }
                                out.write(buffer, 0, length)
                            }
                        }
                        if (name == "manifest.json") {
                            require(destination.length() < 2_000_000) { "Invalid manifest" }
                            manifest = JSONObject(destination.readText(Charsets.UTF_8))
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("Cannot open backup")

            val root = manifest ?: error("Backup manifest missing")
            require(root.optString("format") == "poyara-crm-v1") { "Unsupported backup format" }
            val contacts = root.getJSONArray("contacts")
            val existing = db.all()
            val known = existing.map { it.phone.trim() to it.note }.toMutableSet()
            val pendingGroups = mutableListOf<Pair<Long, String>>()
            val folder = File(context.filesDir, "attachments").apply { mkdirs() }
            var imported = 0
            for (i in 0 until contacts.length()) {
                val item = contacts.getJSONObject(i)
                val name = item.optString("name")
                val phone = item.optString("phone")
                val note = item.optString("note")
                val company = item.optString("company")
                val groupRootPhone = item.optString("groupRootPhone")
                val key = phone.trim() to note
                if ((phone.isNotBlank() || note.isNotBlank()) && key in known) continue
                val tagsJson = item.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tagsJson.length()).map { tagsJson.getString(it) }.toSet()
                val id = db.save(0L, name, phone, note, tags, company)
                if (groupRootPhone.isNotBlank()) pendingGroups += id to groupRootPhone
                known.add(key)
                if (item.has("latitude") && item.has("longitude")) {
                    val lat = item.optDouble("latitude", Double.NaN)
                    val lon = item.optDouble("longitude", Double.NaN)
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                        db.setLocation(id, lat, lon, item.optString("locationSource", "backup"))
                    }
                }
                val notes = item.optJSONArray("notes") ?: JSONArray()
                for (n in 0 until notes.length()) {
                    db.saveNote(0L, id, notes.getJSONObject(n).optString("text"))
                }
                val media = item.optJSONArray("attachments") ?: JSONArray()
                for (m in 0 until media.length()) {
                    val a = media.getJSONObject(m)
                    val path = a.getString("file")
                    val kind = a.getString("kind")
                    require(kind == "audio" || kind == "photo") { "Unsupported attachment" }
                    val source = File(staging, path)
                    require(source.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "Invalid file reference"
                    }
                    if (source.isFile) {
                        val ext = if (kind == "audio") "m4a" else "jpg"
                        val dest = File(folder, "restored_${id}_${System.nanoTime()}.$ext")
                        source.copyTo(dest)
                        db.addAttachment(id, kind, dest.absolutePath)
                    }
                }
                imported++
            }
            val idsByPhone = db.all().filter { it.phone.isNotBlank() }
                .associate { it.phone.trim() to it.id }
            pendingGroups.forEach { (childId, rootPhone) ->
                val rootId = idsByPhone[rootPhone]
                if (rootId != null && rootId != childId) db.setGroup(childId, rootId)
            }
            return imported
        } finally {
            staging.deleteRecursively()
        }
    }
}
