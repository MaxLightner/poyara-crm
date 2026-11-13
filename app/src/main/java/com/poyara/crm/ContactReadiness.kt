package com.poyara.crm

/** Unfinished voice notes remain drafts, never confirmed contacts. */
internal object ContactReadiness {
    fun isComplete(name: String, phone: String): Boolean {
        val normalized = normalizePhone(phone)
        return name.trim().isNotBlank() &&
            normalized.length in 10..13 &&
            normalized.startsWith("0") &&
            normalized.all { it.isDigit() }
    }
}
