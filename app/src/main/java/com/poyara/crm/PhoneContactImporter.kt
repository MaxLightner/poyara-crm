package com.poyara.crm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.database.Cursor

/** Read-only import from the user's phone contacts, never modifies system contacts. */
internal data class PhoneContactCandidate(
    val systemId: Long,
    val displayName: String,
    val phones: List<String>
)

internal object PhoneContactImporter {
    fun read(context: Context): List<PhoneContactCandidate> {
        check(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED) { "اجازه خواندن مخاطبین گوشی داده نشده است" }
        val fields = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val names = linkedMapOf<Long, String>()
        val numbers = linkedMapOf<Long, MutableSet<String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            fields, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)?.trim().orEmpty().take(100)
                val number = normalizePhone(cursor.getString(2).orEmpty())
                if (!ContactReadiness.isComplete(name, number)) continue
                names.putIfAbsent(id, name)
                numbers.getOrPut(id) { linkedSetOf() }.add(number)
            }
        }
        return names.mapNotNull { (id, name) ->
            numbers[id]?.takeIf { it.isNotEmpty() }?.let {
                PhoneContactCandidate(id, name, it.toList())
            }
        }
    }

    fun selectNew(
        candidates: List<PhoneContactCandidate>,
        selectedIds: Set<Long>,
        existing: List<ContactEntry>
    ): List<PhoneContactCandidate> {
        val knownNumbers = existing.flatMap { listOf(it.phone) + it.extraPhones }
            .map(::normalizePhone).toMutableSet()
        return candidates.filter { it.systemId in selectedIds }
            .mapNotNull { candidate ->
                val unique = candidate.phones.map(::normalizePhone)
                    .filter { it.isNotBlank() && it !in knownNumbers }.distinct()
                if (unique.isEmpty()) null
                else {
                    knownNumbers.addAll(unique)
                    candidate.copy(phones = unique)
                }
            }
    }

    fun import(context: Context, selected: List<PhoneContactCandidate>): Int {
        return CrmDb(context.applicationContext).use { db ->
            val toInsert = selectNew(selected, selected.map { it.systemId }.toSet(), db.all())
            var count = 0
            toInsert.forEach { contact ->
                db.save(
                    id = 0L,
                    name = contact.displayName,
                    phone = contact.phones.first(),
                    note = "",
                    tags = emptySet(),
                    extraPhones = contact.phones.drop(1)
                )
                count++
            }
            count
        }
    }
}
